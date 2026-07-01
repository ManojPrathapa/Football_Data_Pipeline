
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All fuzzing activity is written here, not to stdout. One line per event, timestamped
 * to the millisecond. Separate files for the main run log, the crash/failure log, and
 * the coverage log, so each can be tailed independently:
 *
 *   tail -f logs/fuzz_run_<ts>.log
 *   tail -f logs/crashes_<ts>.log
 *   tail -f logs/coverage_<ts>.log
 */
public class FuzzLogger implements Closeable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final ReentrantLock lock = new ReentrantLock();

    private final BufferedWriter runLog;
    private final BufferedWriter crashLog;
    private final BufferedWriter coverageLog;
    public final String runId;

    public FuzzLogger(String logsDir) throws IOException {
        Files.createDirectories(Paths.get(logsDir));
        runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        runLog = Files.newBufferedWriter(Paths.get(logsDir, "fuzz_run_" + runId + ".log"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        crashLog = Files.newBufferedWriter(Paths.get(logsDir, "crashes_" + runId + ".log"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        coverageLog = Files.newBufferedWriter(Paths.get(logsDir, "coverage_" + runId + ".log"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        info("FuzzLogger", "=== Fuzz run " + runId + " started ===");
    }

    private String ts() { return LocalDateTime.now().format(TS); }

    public void info(String component, String msg)  { write(runLog, "INFO", component, msg); }
    public void warn(String component, String msg)  { write(runLog, "WARN", component, msg); }
    public void error(String component, String msg) { write(runLog, "ERROR", component, msg); }

    public void crash(String reason, String csvLine, String detail) {
        lock.lock();
        try {
            crashLog.write("[" + ts() + "] REASON=" + reason + " DETAIL=" + detail + "\nROW=" + csvLine + "\n---\n");
            crashLog.flush();
        } catch (IOException e) {
            // last resort - never let logging crash the fuzzer
        } finally { lock.unlock(); }
    }

    public void coverage(String jsonLine) {
        lock.lock();
        try {
            coverageLog.write("[" + ts() + "] " + jsonLine + "\n");
            coverageLog.flush();
        } catch (IOException e) {
        } finally { lock.unlock(); }
    }

    private void write(BufferedWriter w, String level, String component, String msg) {
        lock.lock();
        try {
            w.write("[" + ts() + "] [" + level + "] [" + component + "] " + msg + "\n");
            w.flush();
        } catch (IOException e) {
        } finally { lock.unlock(); }
    }

    /** Minimal heartbeat to stdout only - keeps the terminal quiet but alive. */
    public void heartbeat(String msg) {
        System.out.println("[heartbeat] " + msg);
    }

    @Override
    public void close() throws IOException {
        info("FuzzLogger", "=== Fuzz run " + runId + " ended ===");
        runLog.close();
        crashLog.close();
        coverageLog.close();
    }
}
