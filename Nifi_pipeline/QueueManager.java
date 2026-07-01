
import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * Filter/remove-fuzz-output step: after each fuzz iteration (or when a connection's queue
 * backs up past a threshold), drop all queued flowfiles on every connection so the pipeline
 * starts the next input clean. Uses POST .../flowfile-queues/{id}/drop-requests, which is
 * the same endpoint the original plan called out.
 */
public class QueueManager {

    private final HttpClient client;
    private final String baseUrl, token, processGroupId;
    private final ProcessorRegistry registry;
    private final FuzzLogger log;

    public QueueManager(HttpClient client, String baseUrl, String token, String processGroupId,
                         ProcessorRegistry registry, FuzzLogger log) {
        this.client = client; this.baseUrl = baseUrl; this.token = token;
        this.processGroupId = processGroupId; this.registry = registry; this.log = log;
    }

    /** Returns total queued flowfile count across all connections (drives the auto-drain trigger). */
    public long totalQueued() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/flow/process-groups/" + processGroupId + "/status"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return 0;
            Map<String, Object> root = MiniJson.parseObject(resp.body());
            Map<String, Object> pgs = MiniJson.asMap(root.get("processGroupStatus"));
            Map<String, Object> agg = MiniJson.asMap(pgs.get("aggregateSnapshot"));
            long queued = MiniJson.asLong(agg.get("flowFilesQueued"), 0);
            return queued;
        } catch (Exception e) {
            log.warn("QueueManager", "totalQueued failed: " + e.getMessage());
            return 0;
        }
    }

    /** Drops every queued flowfile on every discovered connection - resets pipeline state for the next input. */
    public void drainAll() {
        for (ProcessorRegistry.ConnInfo c : registry.connections) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/flowfile-queues/" + c.id + "/drop-requests"))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200 && resp.statusCode() != 202) {
                    log.warn("QueueManager", "drop-request failed for connection " + c.id + " HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                log.warn("QueueManager", "drain failed for connection " + c.id + ": " + e.getMessage());
            }
        }
        log.info("QueueManager", "Drained " + registry.connections.size() + " connection queues");
    }
}
