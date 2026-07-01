import java.io.*;
import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the Source/ directory for new *.csv files while the fuzzer is running, and folds
 * any real rows found into the live corpus - no restart needed. This is what makes "drop in
 * 100 CSVs while it's running" work, and what keeps the fuzzer alive even after the original
 * seed file(s) have been consumed and deleted by GetFile.
 *
 * Race-safety against GetFile (which deletes-on-success within ~5s of a file appearing):
 *   1. Java's WatchService fires ENTRY_CREATE within milliseconds of the file landing -
 *      far faster than GetFile's 5-second poll, so we get there first almost every time.
 *   2. We immediately COPY the new file into fuzz_incoming/ before parsing it, so even in
 *      the rare case GetFile wins the race and deletes the original, our copy survives and
 *      the seed data is never lost.
 *   3. We wait for the file size to stabilize (two checks, 150ms apart) before reading, so
 *      a large file still being written to disk isn't read half-finished.
 *
 * Ignores our own fuzzed output ("fuzz_*.csv") so we don't feed our own mutations back in
 * as if they were new real data - that would be a pointless feedback loop, not new signal.
 */
public class SeedWatcher implements Runnable {

    private final String watchDir;
    private final String incomingCopyDir;
    private final CopyOnWriteArrayList<Seed> liveCorpus;
    private final FuzzLogger log;
    public final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, Integer> countPerType = new ConcurrentHashMap<>();
    private int filesIngested = 0;

    public SeedWatcher(String watchDir, String incomingCopyDir, CopyOnWriteArrayList<Seed> liveCorpus, FuzzLogger log) {
        this.watchDir = watchDir;
        this.incomingCopyDir = incomingCopyDir;
        this.liveCorpus = liveCorpus;
        this.log = log;
    }

    @Override
    public void run() {
        running.set(true);
        try {
            Files.createDirectories(Paths.get(incomingCopyDir));
            WatchService watcher = FileSystems.getDefault().newWatchService();
            Path dir = Paths.get(watchDir);
            dir.register(watcher, ENTRY_CREATE);
            log.info("SeedWatcher", "Watching " + dir.toAbsolutePath() + " for dropped-in CSVs (live, no restart needed)");

            while (running.get()) {
                WatchKey key;
                try {
                    key = watcher.poll(java.util.concurrent.TimeUnit.SECONDS.toMillis(1), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) { break; }
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) continue;
                    @SuppressWarnings("unchecked")
                    Path fileName = ((WatchEvent<Path>) event).context();
                    String name = fileName.toString();
                    if (!name.toLowerCase().endsWith(".csv")) continue;
                    if (name.startsWith("fuzz_")) continue; // don't re-ingest our own mutated output

                    Path fullPath = dir.resolve(fileName);
                    handleNewFile(fullPath, name);
                }
                boolean valid = key.reset();
                if (!valid) {
                    log.error("SeedWatcher", "Watch key invalidated (directory deleted/unmounted?) - stopping watcher");
                    break;
                }
            }
        } catch (Exception e) {
            log.error("SeedWatcher", "Watcher crashed: " + e);
        }
        log.info("SeedWatcher", "Stopped. Total files ingested as seeds: " + filesIngested);
    }

    private void handleNewFile(Path fullPath, String name) {
        try {
            if (!waitForStableSize(fullPath)) {
                log.warn("SeedWatcher", "Skipped " + name + " - file never stabilized (still being written?)");
                return;
            }
            // Copy first, so we still have the data even if GetFile deletes the original moments later.
            Path copy = Paths.get(incomingCopyDir, System.currentTimeMillis() + "_" + name);
            Files.copy(fullPath, copy, StandardCopyOption.REPLACE_EXISTING);

            List<Seed> newSeeds = new ArrayList<>();
            SeedCorpusBuilder.parseFileIntoSeeds(copy.toAbsolutePath().toString(), countPerType, newSeeds, log);

            if (newSeeds.isEmpty()) {
                log.warn("SeedWatcher", "Dropped-in file " + name + " produced 0 valid seeds (wrong column count or already at cap)");
                return;
            }
            liveCorpus.addAll(newSeeds);
            filesIngested++;
            log.info("SeedWatcher", "Ingested " + name + " -> +" + newSeeds.size() + " seeds " +
                    "(live corpus now " + liveCorpus.size() + ", per-type counts=" + countPerType + ")");
        } catch (IOException e) {
            log.warn("SeedWatcher", "Failed to ingest " + name + ": " + e.getMessage());
        }
    }

    /** Simple write-completion check: file size must be identical across two checks 150ms apart. */
    private boolean waitForStableSize(Path path) {
        try {
            long lastSize = -1;
            for (int attempt = 0; attempt < 10; attempt++) {
                if (!Files.exists(path)) return false;
                long size = Files.size(path);
                if (size == lastSize && size > 0) return true;
                lastSize = size;
                Thread.sleep(150);
            }
            return Files.exists(path);
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() { running.set(false); }
}
