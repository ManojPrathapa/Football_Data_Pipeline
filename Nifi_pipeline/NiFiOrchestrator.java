import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class NiFiOrchestrator {

    private static final String NIFI_BASE_URL = "https://localhost:8443/nifi-api";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "Admin123456!";

    // Your exact Process Group ID (unchanged from your original orchestrator)
    private static final String TARGET_PROCESS_GROUP_ID = "40d5d53b-019e-1000-ff35-2afed09dadc1";

    // ---- FUZZER CONFIG ----
    // This is your existing Source/ folder - the same one GetFile already watches. The fuzzer
    // writes fuzzed CSVs here AND reads real CSVs you drop here to build/grow its seed corpus.
    // Relative path assumes you run `java NiFiOrchestrator` from the Nifi_pipeline/ folder root
    // (same place NiFiOrchestrator.java itself lives). If NiFi is containerized and this project
    // folder isn't the actual bind-mounted volume, change this to the real host path instead.
    private static final String FUZZ_SOURCE_DIR = "Source";
    // Where fuzz_logs/, fuzz_seeds/, fuzz_crashes/, fuzz_reports/, fuzz_incoming/ get created.
    private static final String PROJECT_ROOT = ".";

    private HttpClient client;
    private String bearerToken = "";
    private FuzzEngine fuzzEngine;
    private Thread fuzzThread;

    public NiFiOrchestrator() {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            this.client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(10))
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            System.err.println("Failed to build HTTP Client: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        NiFiOrchestrator controller = new NiFiOrchestrator();

        try {
            System.out.println("=========================================");
            System.out.println("    NiFi Live Interactive Controller     ");
            System.out.println("      + Coverage-Guided ETL Fuzzer       ");
            System.out.println("=========================================");

            controller.authenticate();

            Scanner scanner = new Scanner(System.in);
            System.out.println("\n[Commands]: start | stop | status | fuzz | stopfuzz | exit");

            while (true) {
                System.out.print("\nNiFi> ");
                String input = scanner.nextLine().trim().toLowerCase();

                if (input.equals("exit") || input.equals("quit")) {
                    controller.stopFuzzing();
                    System.out.println("Exiting Controller. Goodbye!");
                    break;
                } else if (input.equals("start")) {
                    controller.setProcessGroupState(TARGET_PROCESS_GROUP_ID, "RUNNING");
                } else if (input.equals("stop")) {
                    controller.setProcessGroupState(TARGET_PROCESS_GROUP_ID, "STOPPED");
                } else if (input.equals("status")) {
                    controller.printLiveStatus(TARGET_PROCESS_GROUP_ID);
                } else if (input.equals("fuzz")) {
                    controller.startFuzzing();
                } else if (input.equals("stopfuzz")) {
                    controller.stopFuzzing();
                } else {
                    System.out.println("Unknown command. Use: start, stop, status, fuzz, stopfuzz, exit");
                }
            }
            scanner.close();

        } catch (Exception e) {
            System.err.println("CRITICAL ERROR: " + e.getMessage());
        }
    }

    // ---- fuzz control ----

    public void startFuzzing() {
        if (fuzzThread != null && fuzzThread.isAlive()) {
            System.out.println("    -> Fuzzer already running. Use 'stopfuzz' first.");
            return;
        }
        try {
            // Stop the pipeline first (best-effort) so GetFile can't consume/delete a seed CSV
            // out from under us while we're still scanning Source/ for the initial corpus.
            System.out.println("    -> Pausing pipeline (if running) to safely scan " + FUZZ_SOURCE_DIR + "/ for seeds...");
            try { setProcessGroupState(TARGET_PROCESS_GROUP_ID, "STOPPED"); } catch (Exception ignored) {}
            Thread.sleep(500);

            FuzzLogger fuzzLogger = new FuzzLogger(PROJECT_ROOT + "/fuzz_logs");
            fuzzEngine = new FuzzEngine(client, NIFI_BASE_URL, bearerToken, TARGET_PROCESS_GROUP_ID,
                    FUZZ_SOURCE_DIR, PROJECT_ROOT, fuzzLogger);

            System.out.println("    -> Scanning " + FUZZ_SOURCE_DIR + " for existing CSVs...");
            fuzzEngine.loadInitialSeeds();

            fuzzEngine.init();

            System.out.println("    -> Starting pipeline...");
            setProcessGroupState(TARGET_PROCESS_GROUP_ID, "RUNNING");

            fuzzThread = new Thread(() -> fuzzEngine.run(), "fuzz-engine");
            fuzzThread.setDaemon(true);
            fuzzThread.start();

            System.out.println("    -> Fuzzer running nonstop. Run id: " + fuzzLogger.runId);
            System.out.println("    -> Live logs: fuzz_logs/fuzz_run_" + fuzzLogger.runId + ".log");
            System.out.println("    -> Crashes:   fuzz_logs/crashes_" + fuzzLogger.runId + ".log");
            System.out.println("    -> Coverage:  fuzz_reports/coverage_" + fuzzLogger.runId + ".json (refreshes every 25 iterations)");
            System.out.println("    -> Drop new CSVs into " + FUZZ_SOURCE_DIR + "/ anytime - they're picked up live, no restart needed.");
            System.out.println("    -> Type 'stopfuzz' to stop cleanly.");
        } catch (Exception e) {
            System.out.println("    -> [FAILED] Could not start fuzzer: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stopFuzzing() {
        if (fuzzEngine != null) {
            fuzzEngine.stop();
            System.out.println("    -> Fuzzer stop signal sent (finishing current iteration)...");
            try { if (fuzzThread != null) fuzzThread.join(5000); } catch (InterruptedException ignored) {}
            fuzzEngine = null;
        }
    }

    // ----  original orchestrator ----

    public void authenticate() throws Exception {
        String formData = "username=" + USERNAME + "&password=" + PASSWORD;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NIFI_BASE_URL + "/access/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201 || response.statusCode() == 200) {
            this.bearerToken = response.body();
            System.out.println("[+] System Authenticated Successfully.");
        } else {
            throw new RuntimeException("Authentication failed!");
        }
    }

    public void setProcessGroupState(String processGroupId, String state) throws Exception {
        String jsonPayload = """
                {
                  "id": "%s",
                  "state": "%s"
                }
                """.formatted(processGroupId, state);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NIFI_BASE_URL + "/flow/process-groups/" + processGroupId))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.bearerToken)
                .PUT(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            System.out.println("    -> Pipeline is now " + state);
        } else {
            System.out.println("    -> [FAILED] " + response.statusCode());
        }
    }

    public void printLiveStatus(String processGroupId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NIFI_BASE_URL + "/flow/process-groups/" + processGroupId + "/status"))
                .header("Authorization", "Bearer " + this.bearerToken)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String json = response.body();

            String queued = extractStat(json, "queued");
            String read = extractStat(json, "read");
            String written = extractStat(json, "written");
            String activeThreads = extractThreadCount(json);

            System.out.println("    --- LIVE PIPELINE STATUS ---");
            System.out.println("    Active Threads : " + activeThreads + " (Processors currently working)");
            System.out.println("    Data Queued    : " + queued + " (FlowFiles waiting in lines)");
            System.out.println("    Data Read      : " + read);
            System.out.println("    Data Written   : " + written);
            System.out.println("    ----------------------------");
        } else {
            System.out.println("    -> [FAILED] Could not get status.");
        }
    }

    private String extractStat(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) return matcher.group(1);
        return "0";
    }

    private String extractThreadCount(String json) {
        Pattern pattern = Pattern.compile("\"activeThreadCount\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) return matcher.group(1);
        return "0";
    }
}
