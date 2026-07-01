
import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * Statement-coverage proxy: since NiFi runs in its own JVM, we can't get JaCoCo/Jazzer
 * bytecode coverage of processor internals. Instead we poll each processor's throughput
 * counters (flowFilesIn) via /flow/process-groups/{id}/status. A processor is "hit" for
 * this cycle if flowFilesIn increased since the last poll. Cumulative hit-set / total
 * processor count = our coverage percentage, and the coverage_report.json tracks which
 * processors have NEVER been hit (dead paths worth targeting with more seeds).
 */
public class CoverageTracker {

    private final HttpClient client;
    private final String baseUrl, token, processGroupId;
    private final ProcessorRegistry registry;
    private final FuzzLogger log;

    private final Map<String, Long> lastFlowFilesIn = new HashMap<>();
    public final Set<String> everHit = new LinkedHashSet<>();
    public final Map<String, Long> totalHitsPerProcessor = new HashMap<>();

    public CoverageTracker(HttpClient client, String baseUrl, String token, String processGroupId,
                            ProcessorRegistry registry, FuzzLogger log) {
        this.client = client; this.baseUrl = baseUrl; this.token = token;
        this.processGroupId = processGroupId; this.registry = registry; this.log = log;
    }

    /** Returns the set of processor IDs newly exercised since the previous poll. */
    public Set<String> pollAndDiff() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/process-groups/" + processGroupId + "/status"))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        Set<String> newlyHit = new LinkedHashSet<>();
        if (resp.statusCode() != 200) {
            log.warn("CoverageTracker", "Status poll failed HTTP " + resp.statusCode());
            return newlyHit;
        }

        Map<String, Object> root = MiniJson.parseObject(resp.body());
        Map<String, Object> processGroupStatus = MiniJson.asMap(root.get("processGroupStatus"));
        Map<String, Object> aggregateSnapshot = MiniJson.asMap(processGroupStatus.get("aggregateSnapshot"));

        for (Object po : MiniJson.asList(aggregateSnapshot.get("processorStatusSnapshots"))) {
            Map<String, Object> p = MiniJson.asMap(po);
            Map<String, Object> snap = MiniJson.asMap(p.get("processorStatusSnapshot"));
            String id = MiniJson.asString(snap.get("id"), null);
            if (id == null) continue;
            long flowFilesIn = MiniJson.asLong(snap.get("flowFilesIn"), 0);

            long prev = lastFlowFilesIn.getOrDefault(id, 0L);
            if (flowFilesIn > prev) {
                newlyHit.add(id);
                everHit.add(id);
                totalHitsPerProcessor.merge(id, flowFilesIn - prev, Long::sum);
            }
            lastFlowFilesIn.put(id, flowFilesIn);
        }
        return newlyHit;
    }

    public double coveragePercent() {
        int total = registry.processors.size();
        if (total == 0) return 0.0;
        return 100.0 * everHit.size() / total;
    }

    public List<String> uncoveredProcessorNames() {
        List<String> out = new ArrayList<>();
        for (ProcessorRegistry.ProcInfo p : registry.processors) {
            if (!everHit.contains(p.id)) out.add(p.name);
        }
        return out;
    }

    public String summaryJson(long iteration) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"iteration\":").append(iteration)
          .append(",\"coveragePercent\":").append(String.format("%.1f", coveragePercent()))
          .append(",\"processorsHit\":").append(everHit.size())
          .append(",\"processorsTotal\":").append(registry.processors.size())
          .append(",\"uncovered\":").append(uncoveredProcessorNames())
          .append("}");
        return sb.toString();
    }
}
