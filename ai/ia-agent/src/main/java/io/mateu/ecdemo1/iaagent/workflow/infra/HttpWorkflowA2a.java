package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.*;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Uses the durable A2A profile, not the chat conversation endpoint. Stable child ids survive retries. */
public final class HttpWorkflowA2a implements Delegation {
    private final HttpWorkflowCatalogue catalogue;private final WorkflowIdentity identity;private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public HttpWorkflowA2a(HttpWorkflowCatalogue catalogue,WorkflowIdentity identity,ObjectMapper mapper){this.catalogue=catalogue;this.identity=identity;this.mapper=mapper;}
    @Override public void dispatch(Request parent,Tool tool) {
        try {
            var peer=catalogue.config(parent.rootInvocationId(),parent.agentRef()).config().peersOrEmpty().stream().filter(p->p.id().equals(tool.sourceId)).findFirst().orElseThrow(()->new IllegalArgumentException("Peer not in frozen graph"));
            var metadata=Map.of("workflow",Map.of("parentInvocationId",parent.invocationId(),"toolCallId",tool.id));
            var message=Map.of("role","user","messageId",tool.childId,"parts",List.of(Map.of("kind","data","data",mapper.readTree(tool.arguments))));
            var body=Map.of("jsonrpc","2.0","id",tool.childId,"method","message/send","params",Map.of("message",message,"metadata",metadata));
            var result=send(peer.a2aUrl(),body,parent.deadlineEpochMillis());
            while(Set.of("submitted","working").contains(result.path("result").path("status").path("state").asText())) {
                if(System.currentTimeMillis()>=parent.deadlineEpochMillis())throw new DelegationUnavailable("Child remains in progress at deadline");
                // Bounded poll; ledger and task id, rather than this waiting thread, own progress.
                Thread.sleep(100);
                result=send(peer.a2aUrl(),Map.of("jsonrpc","2.0","id",tool.childId,"method","tasks/get","params",Map.of("id",tool.childId)),parent.deadlineEpochMillis());
            }
        }catch(DelegationUnavailable ex){throw ex;}catch(Exception ex){throw new DelegationUnavailable("Durable child outcome pending; retry tasks/get",ex);}
    }
    private JsonNode send(String url,Object body,long deadline)throws Exception {
        var timeout=Duration.ofMillis(Math.max(1,deadline-System.currentTimeMillis()));
        var res=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization",identity.authorization()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        if(res.statusCode()/100!=2)throw new DelegationUnavailable("A2A HTTP "+res.statusCode());
        var node=mapper.readTree(res.body());if(node.has("error"))throw new IllegalArgumentException("A2A refused trusted delegation: "+node.path("error"));return node;
    }
}
