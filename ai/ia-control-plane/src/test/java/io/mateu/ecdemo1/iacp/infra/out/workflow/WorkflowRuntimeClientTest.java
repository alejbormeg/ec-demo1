package io.mateu.ecdemo1.iacp.infra.out.workflow;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.net.http.HttpRequest;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowRuntimeClientTest {
    static final String LIST = "[{\"id\":\"invocation\",\"agentId\":\"synthetic\",\"state\":\"REVIEW_REQUIRED\",\"requestDigest\":\"input-digest\","
            + "\"unknownCalls\":[{\"toolCallId\":\"call-1\",\"toolDigest\":\"tool-digest\",\"toolName\":\"update_case\",\"arguments\":\"{}\"}]}]";
    @Test void forwardsActualOperatorAndBindsReviewToBothDigests() {
        var requests = new ArrayList<HttpRequest>();
        var client = new WorkflowRuntimeClient("http://runtime.synthetic:8095", request -> {
            requests.add(request);
            return new WorkflowRuntimeClient.Reply(200, request.method().equals("GET") ? LIST : "{}");
        });
        var review = new WorkflowRuntimeClient.Review("call-1", "tool-digest", "input-digest", "{\"updated\":true}", "Synthetic transaction checked");
        client.review("invocation", review, "Bearer actual-operator-token");
        assertEquals(2, requests.size());
        assertEquals("POST", requests.getLast().method());
        assertEquals("/internal/workflow-agent-executions/invocation/review", requests.getLast().uri().getPath());
        requests.forEach(r -> assertEquals("Bearer actual-operator-token", r.headers().firstValue("Authorization").orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> client.review("invocation", new WorkflowRuntimeClient.Review(
                "call-1", "changed-tool", "input-digest", "{}", "Checked"), "Bearer actual-operator-token"));
        assertThrows(IllegalArgumentException.class, () -> client.review("invocation", new WorkflowRuntimeClient.Review(
                "call-1", "tool-digest", "changed-request", "{}", "Checked"), "Bearer actual-operator-token"));
        assertEquals(1, requests.stream().filter(r -> r.method().equals("POST")).count());
    }
    @Test void evidenceAndIdentifiersAreRequiredAndTokensAreNeverAddedToExceptions() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRuntimeClient.Review("call", "tool", "request", "{}", " "));
        assertThrows(IllegalArgumentException.class, () -> WorkflowRuntimeClient.id("../escape"));
        var client = new WorkflowRuntimeClient("http://runtime.synthetic:8095", request -> new WorkflowRuntimeClient.Reply(403, "sensitive remote payload"));
        var exception = assertThrows(IllegalArgumentException.class, () -> client.list("Bearer synthetic-operator"));
        assertFalse(exception.getMessage().contains("synthetic-operator"));
        assertFalse(exception.getMessage().contains("sensitive remote payload"));
        assertThrows(IllegalArgumentException.class, () -> client.list(null));
    }
    @Test void returnsRemoteDetailsAsEscapableJsonTextAndCancelsSelectedChain() {
        var requests = new ArrayList<HttpRequest>();
        var client = new WorkflowRuntimeClient("http://runtime.synthetic:8095", request -> {
            requests.add(request); return new WorkflowRuntimeClient.Reply(200, "{\"execution\":{},\"chain\":[]}");
        });
        assertTrue(client.details("invocation", "Bearer operator").contains("execution"));
        client.cancel("invocation", "Bearer operator");
        assertEquals("/internal/workflow-agent-executions/invocation/cancel", requests.getLast().uri().getPath());
    }
}
