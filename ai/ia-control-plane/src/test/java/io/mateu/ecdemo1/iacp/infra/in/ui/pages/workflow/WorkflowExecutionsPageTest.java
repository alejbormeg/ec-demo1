package io.mateu.ecdemo1.iacp.infra.in.ui.pages.workflow;

import io.mateu.ecdemo1.iacp.infra.out.workflow.WorkflowRuntimeClient;
import io.mateu.uidl.interfaces.HttpRequest;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowExecutionsPageTest {
    @Test void reviewUsesLoadedDigestsAndEvidenceAndClearsApprovalAfterSubmit() {
        var submitted = new AtomicInteger();
        var client = new WorkflowRuntimeClient("http://runtime.synthetic", request -> {
            if (request.method().equals("POST")) submitted.incrementAndGet();
            var json = request.uri().getPath().endsWith("executions")
                    ? "[{\"id\":\"run\",\"agentId\":\"synthetic\",\"state\":\"REVIEW_REQUIRED\",\"requestDigest\":\"request-digest\",\"unknownCalls\":[{\"toolCallId\":\"call\",\"toolDigest\":\"tool-digest\",\"toolName\":\"update\",\"arguments\":\"{}\"}]}]"
                    : "{\"execution\":{},\"chain\":[]}";
            return new WorkflowRuntimeClient.Reply(200, json);
        });
        var http = (HttpRequest) Proxy.newProxyInstance(HttpRequest.class.getClassLoader(), new Class<?>[]{HttpRequest.class},
                (proxy, method, args) -> method.getName().equals("getHeaderValue") ? "Bearer actual-operator" : null);
        var page = new WorkflowExecutionsPage(client); page.executionId = "run";
        page.loadExecution(http); assertEquals("request-digest", page.requestDigest);
        page.toolCallId = "call"; page.loadToolCall(http); assertEquals("tool-digest", page.toolDigest);
        page.confirmedResult = "{}";
        assertThrows(IllegalArgumentException.class, () -> page.confirmReconciliation(http));
        assertEquals(0, submitted.get());
        page.evidence = "Synthetic transaction verified"; page.confirmReconciliation(http);
        assertEquals(1, submitted.get()); assertNull(page.toolDigest); assertNull(page.evidence); assertNull(page.confirmedResult);
    }
}
