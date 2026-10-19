package io.mateu.ecdemo1.iacp.infra.out.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Stateless operator proxy. Forward the actual bearer; never substitute the worker's identity. */
@Component
public class WorkflowRuntimeClient {
    public record UnknownCall(String toolCallId, String toolName, String toolDigest, String arguments) {
        @Override public String toString() { return "UnknownCall[" + toolCallId + "]"; }
    }
    public record Summary(String id, String rootId, String agentId, String state, String deadline,
                          long inputTokens, long outputTokens, String requestDigest,
                          String snapshotDigest, String catalogueRevision, String error,
                          List<UnknownCall> unknownCalls, List<Map<String, Object>> traces) {
        @Override public String toString() { return "WorkflowExecution[" + id + "," + state + "]"; }
    }
    public record Review(String toolCallId, String toolDigest, String requestDigest,
                         String confirmedResult, String evidence) {
        public Review {
            for (var value : Arrays.asList(toolCallId, toolDigest, requestDigest, confirmedResult, evidence))
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Call identity, both digests, confirmed result and evidence are required");
        }
        @Override public String toString() { return "WorkflowReview[" + toolCallId + "]"; }
    }
    public record Reply(int status, String body) {}
    @FunctionalInterface public interface Exchange { Reply send(HttpRequest request) throws Exception; }
    private final String baseUrl;
    private final Exchange exchange;
    @Autowired
    public WorkflowRuntimeClient(@Value("${cp.workflow.runtime-url:http://ia-workflow-worker:8095}") String baseUrl) {
        this(baseUrl, defaultExchange());
    }
    public WorkflowRuntimeClient(String baseUrl, Exchange exchange) {
        var base = URI.create(baseUrl);
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Invalid workflow runtime URL");
        this.baseUrl = baseUrl.replaceAll("/+$", "") + "/internal/workflow-agent-executions";
        this.exchange = exchange;
    }
    private static Exchange defaultExchange() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body());
        };
    }
    public List<Summary> list(String bearer) {
        try { return JsonDocuments.MAPPER.readValue(call("GET", "", null, bearer), new TypeReference<List<Summary>>() {}); }
        catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Invalid workflow runtime execution list"); }
    }
    public String details(String executionId, String bearer) {
        var json = call("GET", "/" + id(executionId), null, bearer);
        // Parsing also prevents a remote HTML error from being shown as execution data.
        return pretty(JsonDocuments.readMap(json));
    }
    public void review(String executionId, Review review, String bearer) {
        var current = list(bearer).stream().filter(e -> e.id().equals(executionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Execution no longer exists"));
        if (!"REVIEW_REQUIRED".equals(current.state()) || !review.requestDigest().equals(current.requestDigest()))
            throw new IllegalArgumentException("Execution changed; reload it before reviewing");
        var call = current.unknownCalls() == null ? Optional.<UnknownCall>empty() : current.unknownCalls().stream()
                .filter(t -> t.toolCallId().equals(review.toolCallId())).findFirst();
        if (call.isEmpty() || !review.toolDigest().equals(call.get().toolDigest()))
            throw new IllegalArgumentException("Tool input changed; reload it before reviewing");
        call("POST", "/" + id(executionId) + "/review", review, bearer);
    }
    public void cancel(String executionId, String bearer) { call("POST", "/" + id(executionId) + "/cancel", Map.of(), bearer); }
    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,512}") || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("A valid execution identifier is required");
        return value;
    }
    private String call(String method, String path, Object body, String bearer) {
        if (bearer == null || bearer.length() > 32768 || !bearer.matches("Bearer [A-Za-z0-9._~+/=-]+"))
            throw new IllegalArgumentException("A signed operator bearer token is required");
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", bearer).header("Accept", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(JsonDocuments.write(body), StandardCharsets.UTF_8));
        try {
            var reply = exchange.send(builder.build());
            if (reply.status() == 401 || reply.status() == 403)
                throw new IllegalArgumentException("The workflow runtime requires a signed ai-admin operator token");
            if (reply.status() < 200 || reply.status() >= 300)
                throw new IllegalStateException("Workflow runtime refused the operation (HTTP " + reply.status() + ")");
            return reply.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Workflow runtime request interrupted");
        } catch (IllegalArgumentException | IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Workflow runtime is unavailable"); }
    }
    public static String pretty(Object value) {
        try { return JsonDocuments.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid workflow view data"); }
    }
}
