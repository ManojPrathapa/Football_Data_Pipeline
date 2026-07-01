import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The full closed loop, now with a DYNAMIC seed pool:
 *   - at startup, scans every *.csv already in Source/ to build the initial corpus
 *   - a SeedWatcher thread keeps watching Source/ for the entire run, so any CSV you drop
 *     in later (one, or a hundred at once) gets folded into the live corpus within ~1s
 *   - the corpus survives even after GetFile deletes the original files, because both the
 *     startup scan and the watcher copy data into Seed objects in memory immediately
 *   - runs nonstop (by design - you stop it with `stopfuzz`, there's no auto-exit)
 *   - a corpus size cap with lowest-energy eviction keeps memory bounded across long runs
 *
 * Statement coverage    -> CoverageTracker (processor flowFilesIn deltas)
 * Syntax-aware fuzzing   -> Mutators (M1-M7), always column-count-valid except M4 by design
 * Seeds / syntax seeds   -> SeedCorpusBuilder (startup) + SeedWatcher (live, dynamic)
 * Coverage report        -> reports/coverage_<runId>.json, refreshed every REPORT_EVERY iters
 * Coverage criteria      -> % of live-canvas processors ever exercised
 * Error detection        -> BulletinMonitor (NiFi bulletin board = real processor exceptions)
 * Assertion / pass-fail  -> any ERROR/WARN bulletin in the poll window after injection = FAIL
 * Filter & cleanup       -> QueueManager drains all connections on threshold or on FAIL
 */
public class FuzzEngine {

    private static final long POLL_AFTER_INJECT_MS = 900;
    private static final long ITERATION_GAP_MS = 350;
    private static final int REPORT_EVERY = 25;
    private static final long QUEUE_DRAIN_THRESHOLD = 500;
    private static final int HEARTBEAT_EVERY = 20;
    private static final int MAX_CORPUS_SIZE = 500; // eviction kicks in above this, run is meant to be nonstop

    private final HttpClient client;
    private final String baseUrl, token, processGroupId, sourceDir, projectRoot;
    private final String seedsDir, logsDir, crashesDir, reportsDir, incomingDir;
    private final FuzzLogger log;
    public final AtomicBoolean running = new AtomicBoolean(false);

    private ProcessorRegistry registry;
    private CoverageTracker coverage;
    private BulletinMonitor bulletins;
    private QueueManager queues;
    private SeedWatcher seedWatcher;
    private Thread watcherThread;
    private final CopyOnWriteArrayList<Seed> corpus = new CopyOnWriteArrayList<>();
    private final Random rnd = new Random();

    private long iteration = 0, crashCount = 0, passCount = 0, failCount = 0;

    public FuzzEngine(HttpClient client, String baseUrl, String token, String processGroupId,
                       String sourceDir, String projectRoot, FuzzLogger log) {
        this.client = client; this.baseUrl = baseUrl; this.token = token;
        this.processGroupId = processGroupId; this.sourceDir = sourceDir; this.projectRoot = projectRoot;
        this.seedsDir = projectRoot + "/fuzz_seeds";
        this.logsDir = projectRoot + "/fuzz_logs";
        this.crashesDir = projectRoot + "/fuzz_crashes";
        this.reportsDir = projectRoot + "/fuzz_reports";
        this.incomingDir = projectRoot + "/fuzz_incoming"; // safety copies of dropped-in CSVs, see SeedWatcher
        this.log = log;
    }

    /** Step 1: build the initial corpus from whatever CSVs are already sitting in Source/. Call this
     *  BEFORE the pipeline is set RUNNING, so GetFile can't delete a seed file out from under us. */
    public void loadInitialSeeds() throws IOException {
        List<Seed> initial = SeedCorpusBuilder.buildFromDirectory(sourceDir, log);
        corpus.addAll(initial);
        SeedCorpusBuilder.persist(initial, seedsDir);
        if (corpus.isEmpty()) {
            log.warn("FuzzEngine", "Corpus is EMPTY at startup - fuzzer will idle until you drop a CSV into " + sourceDir);
        }
    }

    /** Step 2: discover the live canvas + wire up coverage/bulletin/queue monitors. Safe to call anytime after auth. */
    public void init() throws Exception {
        Files.createDirectories(Paths.get(sourceDir));
        Files.createDirectories(Paths.get(crashesDir));
        Files.createDirectories(Paths.get(reportsDir));

        registry = ProcessorRegistry.discover(client, baseUrl, token, processGroupId, log);
        coverage = new CoverageTracker(client, baseUrl, token, processGroupId, registry, log);
        bulletins = new BulletinMonitor(client, baseUrl, token, log);
        queues = new QueueManager(client, baseUrl, token, processGroupId, registry, log);

        // baseline poll so the first real diff isn't inflated by pre-existing throughput
        coverage.pollAndDiff();
        bulletins.pollNew();
        log.info("FuzzEngine", "Initialized. Corpus size=" + corpus.size() +
                ", processors=" + registry.processors.size() + ", connections=" + registry.connections.size());
    }

    /** Step 3: start the live directory watcher + the nonstop fuzz loop. Call after the pipeline is RUNNING. */
    public void run() {
        running.set(true);

        seedWatcher = new SeedWatcher(sourceDir, incomingDir, corpus, log);
        watcherThread = new Thread(seedWatcher, "seed-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();

        log.info("FuzzEngine", "Fuzzing loop started (nonstop until stopfuzz/exit). " +
                "Drop new CSVs into " + sourceDir + " anytime - they'll be picked up live.");
        while (running.get()) {
            try {
                if (corpus.isEmpty()) {
                    Thread.sleep(1000); // nothing to mutate yet, wait for the watcher to find something
                    continue;
                }
                iterateOnce();
            } catch (Exception e) {
                log.error("FuzzEngine", "Iteration " + iteration + " threw: " + e);
            }
            try { Thread.sleep(ITERATION_GAP_MS); } catch (InterruptedException ie) { break; }
        }

        if (seedWatcher != null) seedWatcher.stop();
        log.info("FuzzEngine", "Fuzzing loop stopped. Total iterations=" + iteration +
                ", pass=" + passCount + ", fail=" + failCount + ", crashes=" + crashCount);
        writeCoverageReport();
    }

    public void stop() { running.set(false); }

    private void iterateOnce() throws Exception {
        iteration++;

        Seed base = pickSeed();
        List<String> appliedMutations = new ArrayList<>();
        String[] mutatedRow = Mutators.mutate(base.row, rnd, appliedMutations);
        base.timesChosen++;

        String fname = "fuzz_" + log.runId + "_" + iteration + ".csv";
        Path target = Paths.get(sourceDir, fname);
        writeCsvFile(target, mutatedRow);

        log.info("FuzzEngine", "iter=" + iteration + " seed=" + base.id + "(" + base.eventType + ")" +
                " mutations=" + appliedMutations + " file=" + fname + " corpusSize=" + corpus.size());

        Thread.sleep(POLL_AFTER_INJECT_MS);

        Set<String> newlyHit = coverage.pollAndDiff();
        List<BulletinMonitor.Bulletin> newBulletins = bulletins.pollNew();

        boolean failed = false;
        for (BulletinMonitor.Bulletin b : newBulletins) {
            if ("ERROR".equalsIgnoreCase(b.level) || "WARN".equalsIgnoreCase(b.level)) {
                failed = true;
                crashCount++;
                String detail = b.toString() + " | mutations=" + appliedMutations + " | sourceSeed=" + base.eventType;
                log.crash("BULLETIN_" + b.level, String.join(";", mutatedRow), detail);
                saveCrashCase(mutatedRow, detail);
                log.error("FuzzEngine", "FAIL iter=" + iteration + " -> " + detail);
            }
        }

        if (!newlyHit.isEmpty()) {
            base.novelHits++;
            Seed promoted = new Seed(mutatedRow, base.eventType, false);
            promoted.lineage.addAll(base.lineage);
            promoted.lineage.addAll(appliedMutations);
            corpus.add(promoted);
            log.info("FuzzEngine", "NEW COVERAGE iter=" + iteration + " processors=" +
                    newlyHitNames(newlyHit) + " -> seed promoted (corpus size=" + corpus.size() + ")");
        }

        if (failed) failCount++; else passCount++;

        enforceCorpusCap();

        long queued = queues.totalQueued();
        if (queued > QUEUE_DRAIN_THRESHOLD || failed) {
            queues.drainAll();
        }

        if (iteration % REPORT_EVERY == 0) writeCoverageReport();
        if (iteration % HEARTBEAT_EVERY == 0) {
            log.heartbeat("iter=" + iteration + " coverage=" + String.format("%.1f", coverage.coveragePercent()) +
                    "% pass=" + passCount + " fail=" + failCount + " crashes=" + crashCount +
                    " corpus=" + corpus.size() + "  (full detail in fuzz_logs/fuzz_run_" + log.runId + ".log)");
        }
    }

    /** Keeps memory bounded across a genuinely nonstop run: drop the lowest-energy (least useful) seeds first. */
    private void enforceCorpusCap() {
        if (corpus.size() <= MAX_CORPUS_SIZE) return;
        List<Seed> sorted = new ArrayList<>(corpus);
        sorted.sort(Comparator.comparingDouble(Seed::energy));
        int toRemove = corpus.size() - MAX_CORPUS_SIZE;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            corpus.remove(sorted.get(i));
        }
        log.info("FuzzEngine", "Corpus cap reached (" + MAX_CORPUS_SIZE + ") - evicted " + toRemove + " lowest-energy seeds");
    }

    private List<String> newlyHitNames(Set<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(registry.nameOf(id));
        return out;
    }

    private Seed pickSeed() {
        double totalEnergy = 0;
        for (Seed s : corpus) totalEnergy += s.energy();
        double r = rnd.nextDouble() * totalEnergy;
        double acc = 0;
        for (Seed s : corpus) {
            acc += s.energy();
            if (acc >= r) return s;
        }
        return corpus.get(corpus.size() - 1);
    }

    private void writeCsvFile(Path target, String[] row) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write(CsvSchema.header());
            w.newLine();
            w.write(String.join(";", row));
            w.newLine();
        }
    }

    private void saveCrashCase(String[] row, String detail) {
        try {
            String fname = "crash_" + log.runId + "_" + iteration + ".csv";
            Path p = Paths.get(crashesDir, fname);
            try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                w.write("# " + detail.replace("\n", " "));
                w.newLine();
                w.write(CsvSchema.header());
                w.newLine();
                w.write(String.join(";", row));
                w.newLine();
            }
        } catch (IOException e) {
            log.warn("FuzzEngine", "Failed to persist crash case: " + e.getMessage());
        }
    }

    private void writeCoverageReport() {
        try {
            String report = "{\n" +
                    "  \"runId\": \"" + log.runId + "\",\n" +
                    "  \"generatedAt\": \"" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\",\n" +
                    "  \"iterations\": " + iteration + ",\n" +
                    "  \"pass\": " + passCount + ",\n" +
                    "  \"fail\": " + failCount + ",\n" +
                    "  \"crashes\": " + crashCount + ",\n" +
                    "  \"corpusSize\": " + corpus.size() + ",\n" +
                    "  \"coveragePercent\": " + String.format("%.2f", coverage.coveragePercent()) + ",\n" +
                    "  \"processorsHit\": " + coverage.everHit.size() + ",\n" +
                    "  \"processorsTotal\": " + registry.processors.size() + ",\n" +
                    "  \"uncoveredProcessors\": " + jsonArr(coverage.uncoveredProcessorNames()) + "\n" +
                    "}\n";
            Path p = Paths.get(reportsDir, "coverage_" + log.runId + ".json");
            Files.writeString(p, report, StandardCharsets.UTF_8);
            log.coverage(coverage.summaryJson(iteration));
        } catch (IOException e) {
            log.warn("FuzzEngine", "Failed to write coverage report: " + e.getMessage());
        }
    }

    private String jsonArr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\"").append(items.get(i).replace("\"", "'")).append("\"");
            if (i < items.size() - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }
}
