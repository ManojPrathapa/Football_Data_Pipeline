
import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * The failure/assertion oracle. NiFi surfaces processor exceptions (Jolt parse errors,
 * SQLite constraint violations, record-conversion failures, etc.) as bulletins in real
 * time via GET /nifi-api/flow/bulletin-board. We poll with `after=<lastId>` so each
 * bulletin is only seen once. Any ERROR/WARN bulletin during a fuzz iteration = FAIL
 * for that input; anything else = PASS. This is the pass/fail evaluation step.
 */
public class BulletinMonitor {

    public static class Bulletin {
        public long id;
        public String level, message, sourceId, sourceName, timestamp;
        public String toString() {
            return "[" + level + "] " + sourceName + ": " + message;
        }
    }

    private final HttpClient client;
    private final String baseUrl, token;
    private final FuzzLogger log;
    private long lastBulletinId = -1;

    public BulletinMonitor(HttpClient client, String baseUrl, String token, FuzzLogger log) {
        this.client = client; this.baseUrl = baseUrl; this.token = token; this.log = log;
    }

    public List<Bulletin> pollNew() {
        List<Bulletin> out = new ArrayList<>();
        try {
            String url = baseUrl + "/flow/bulletin-board" + (lastBulletinId >= 0 ? "?after=" + lastBulletinId : "");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return out;

            Map<String, Object> root = MiniJson.parseObject(resp.body());
            Map<String, Object> board = MiniJson.asMap(root.get("bulletinBoard"));
            for (Object bo : MiniJson.asList(board.get("bulletins"))) {
                Map<String, Object> b = MiniJson.asMap(bo);
                Map<String, Object> bulletin = MiniJson.asMap(b.get("bulletin"));
                Bulletin bb = new Bulletin();
                bb.id = MiniJson.asLong(b.get("id"), MiniJson.asLong(bulletin.get("id"), 0));
                bb.level = MiniJson.asString(bulletin.get("level"), "INFO");
                bb.message = MiniJson.asString(bulletin.get("message"), "");
                bb.sourceId = MiniJson.asString(bulletin.get("sourceId"), "");
                bb.sourceName = MiniJson.asString(bulletin.get("sourceName"), "");
                bb.timestamp = MiniJson.asString(bulletin.get("timestamp"), "");
                if (bb.id > lastBulletinId) {
                    out.add(bb);
                    lastBulletinId = Math.max(lastBulletinId, bb.id);
                }
            }
        } catch (Exception e) {
            log.warn("BulletinMonitor", "poll failed: " + e.getMessage());
        }
        return out;
    }
}
