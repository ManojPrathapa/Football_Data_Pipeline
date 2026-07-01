import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the seed corpus from EVERY CSV sitting in the watched directory at startup
 * (not just one hardcoded file), bucketed by `type`. This is also the shared parsing
 * logic SeedWatcher uses later for CSVs dropped in mid-run.
 *
 * A global per-type cap keeps the corpus from exploding if you drop in 100 large files
 * at once - we only need a handful of representative real rows per event type, the
 * mutation engine does the rest.
 */
public class SeedCorpusBuilder {

    public static final int GLOBAL_CAP_PER_TYPE = 15;

    /** Scans every *.csv file already present in dir at startup. Read-only - never deletes/moves anything. */
    public static List<Seed> buildFromDirectory(String dir, FuzzLogger log) throws IOException {
        List<Seed> seeds = new ArrayList<>();
        Map<String, Integer> countPerType = new ConcurrentHashMap<>();

        File folder = new File(dir);
        File[] files = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".csv"));
        if (files == null || files.length == 0) {
            log.warn("SeedCorpusBuilder", "No CSV files found in " + dir + " at startup - corpus will be empty until you drop one in");
            return seeds;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File f : files) {
            int before = seeds.size();
            parseFileIntoSeeds(f.getAbsolutePath(), countPerType, seeds, log);
            log.info("SeedCorpusBuilder", "Startup scan: " + f.getName() + " contributed " + (seeds.size() - before) + " seeds");
            if (allTypesCapped(countPerType)) break;
        }
        log.info("SeedCorpusBuilder", "Startup corpus built: " + seeds.size() + " seeds from " + files.length +
                " file(s), event types=" + countPerType);
        return seeds;
    }

    /** Parses one CSV file, adds up to GLOBAL_CAP_PER_TYPE rows per type (respecting counts already seen) into `out`. */
    public static void parseFileIntoSeeds(String csvPath, Map<String, Integer> countPerType,
                                           List<Seed> out, FuzzLogger log) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            if (headerLine == null) return;
            int typeCol = CsvSchema.col("type");

            String line;
            while ((line = br.readLine()) != null) {
                if (allTypesCapped(countPerType)) break;
                String[] fields = line.split(";", -1);
                if (fields.length != CsvSchema.COLUMNS.length) continue; // skip malformed rows, don't crash the scan
                String type = fields[typeCol];
                int have = countPerType.getOrDefault(type, 0);
                if (have >= GLOBAL_CAP_PER_TYPE) continue;
                countPerType.put(type, have + 1);
                out.add(new Seed(fields, type, true));
            }
        } catch (IOException e) {
            log.warn("SeedCorpusBuilder", "Failed to parse " + csvPath + ": " + e.getMessage());
        }
    }

    private static boolean allTypesCapped(Map<String, Integer> countPerType) {
        for (String t : CsvSchema.ROUTED_TYPES) {
            if (countPerType.getOrDefault(t, 0) < GLOBAL_CAP_PER_TYPE) return false;
        }
        return true;
    }

    /** Writes each seed to disk as a standalone one-row CSV for inspection/reuse. */
    public static void persist(List<Seed> seeds, String outDir) throws IOException {
        Files.createDirectories(Paths.get(outDir));
        for (Seed s : seeds) {
            String fname = "seed_" + s.id + "_" + safe(s.eventType) + ".csv";
            Path p = Paths.get(outDir, fname);
            try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                w.write(CsvSchema.header());
                w.newLine();
                w.write(s.csvLine());
                w.newLine();
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
