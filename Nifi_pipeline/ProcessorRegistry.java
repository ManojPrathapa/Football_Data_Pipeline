
import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * Discovers the ACTUAL processors/connections on the running NiFi canvas at startup,
 * instead of trusting hardcoded UUIDs from an exported flow.json (which can drift if the
 * flow was re-imported). This makes coverage tracking robust to ID changes as long as the
 * process group ID (TARGET_PROCESS_GROUP_ID) and processor names stay the same.
 */
public class ProcessorRegistry {

    public static class ProcInfo {
        public String id, name, type;
        public ProcInfo(String id, String name, String type) { this.id = id; this.name = name; this.type = type; }
    }

    public static class ConnInfo {
        public String id, name, sourceId, destId;
        public ConnInfo(String id, String name, String sourceId, String destId) {
            this.id = id; this.name = name; this.sourceId = sourceId; this.destId = destId;
        }
    }

    public final List<ProcInfo> processors = new ArrayList<>();
    public final List<ConnInfo> connections = new ArrayList<>();

    public static ProcessorRegistry discover(HttpClient client, String baseUrl, String token,
                                              String processGroupId, FuzzLogger log) throws Exception {
        ProcessorRegistry reg = new ProcessorRegistry();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/process-groups/" + processGroupId))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to discover process group (HTTP " + resp.statusCode() + "): " + resp.body());
        }

        Map<String, Object> root = MiniJson.parseObject(resp.body());
        Map<String, Object> processGroupFlow = MiniJson.asMap(root.get("processGroupFlow"));
        Map<String, Object> flow = MiniJson.asMap(processGroupFlow.get("flow"));

        for (Object po : MiniJson.asList(flow.get("processors"))) {
            Map<String, Object> p = MiniJson.asMap(po);
            Map<String, Object> component = MiniJson.asMap(p.get("component"));
            String id = MiniJson.asString(component.get("id"), null);
            String name = MiniJson.asString(component.get("name"), "unknown");
            String type = MiniJson.asString(component.get("type"), "unknown");
            if (id != null) reg.processors.add(new ProcInfo(id, name, type));
        }

        for (Object co : MiniJson.asList(flow.get("connections"))) {
            Map<String, Object> c = MiniJson.asMap(co);
            Map<String, Object> component = MiniJson.asMap(c.get("component"));
            String id = MiniJson.asString(component.get("id"), null);
            String name = MiniJson.asString(component.get("name"), "");
            Map<String, Object> source = MiniJson.asMap(component.get("source"));
            Map<String, Object> dest = MiniJson.asMap(component.get("destination"));
            String srcId = MiniJson.asString(source.get("id"), null);
            String dstId = MiniJson.asString(dest.get("id"), null);
            if (id != null) reg.connections.add(new ConnInfo(id, name, srcId, dstId));
        }

        log.info("ProcessorRegistry", "Discovered " + reg.processors.size() + " processors, " +
                reg.connections.size() + " connections on live canvas");
        for (ProcInfo p : reg.processors) log.info("ProcessorRegistry", "  processor: " + p.name + " (" + p.type + ") id=" + p.id);

        if (reg.processors.isEmpty()) {
            log.error("ProcessorRegistry", "No processors discovered - check TARGET_PROCESS_GROUP_ID and auth token");
        }
        return reg;
    }

    public String nameOf(String processorId) {
        for (ProcInfo p : processors) if (p.id.equals(processorId)) return p.name;
        return processorId;
    }
}
