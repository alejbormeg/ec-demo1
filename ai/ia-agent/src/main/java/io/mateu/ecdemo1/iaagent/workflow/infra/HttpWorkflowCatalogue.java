package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.*;
import io.mateu.ecdemo1.iaagent.config.AgentConfig;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.Request;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

public final class HttpWorkflowCatalogue implements Catalogue {
    public record AgentWire(AgentConfig config,Map<String,Object> outputSchema) {}
    private final String url;private final WorkflowIdentity identity;private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public HttpWorkflowCatalogue(String url,WorkflowIdentity identity,ObjectMapper mapper){this.url=url.replaceAll("/+$","");this.identity=identity;this.mapper=mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);}
    @Override public Snapshot resolve(Request request,String digest) {
        var body=new LinkedHashMap<String,Object>();body.put("invocationId",request.invocationId());body.put("agentId",request.agentRef());body.put("requestDigest",digest);
        body.put("mcp",request.mcp());body.put("rag",request.rag());body.put("peers",request.peers());
        return project(send("POST","/internal/workflow-agent-executions/resolve",body));
    }
    @Override public Snapshot snapshot(String rootId){return project(rawSnapshot(rootId));}
    JsonNode rawSnapshot(String rootId){return send("GET","/internal/workflow-agent-executions/"+segment(rootId)+"/snapshot",null);}
    AgentWire config(String rootId,String agentId) {
        var node=rawSnapshot(rootId).path("agents").path(agentId);
        if(node.isMissingNode())throw new IllegalArgumentException("Agent missing from frozen graph: "+agentId);
        try{return new AgentWire(mapper.treeToValue(node,AgentConfig.class),mapper.convertValue(node.path("outputSchema"),Map.class));}
        catch(Exception ex){throw new IllegalArgumentException("Invalid frozen agent",ex);}
    }
    private Snapshot project(JsonNode json) {
        var agents=new LinkedHashMap<String,Agent>();json.path("agents").fields().forEachRemaining(e->{var n=e.getValue();agents.put(e.getKey(),new Agent(e.getKey(),n.path("systemPrompt").asText(""),mapper.convertValue(n.path("outputSchema"),Map.class)));});
        return new Snapshot(json.path("snapshotId").asText(),json.path("digest").asText(),json.path("catalogueRevision").asText(),agents);
    }
    @Override public void authorize(Request request){send("POST","/internal/workflow-agent-executions/authorize",Map.of("invocationId",request.invocationId(),"rootInvocationId",request.rootInvocationId(),"agentId",request.agentRef()));}
    @Override public void usage(String payload){try{var node=mapper.readTree(payload);send("POST","/internal/workflow-agent-executions/"+segment(node.path("rootInvocationId").asText())+"/usage",mapper.convertValue(node,Map.class));}catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException(ex);}}
    JsonNode rag(Request request,String ragId,Map<String,Object> args,int topK){var body=new HashMap<String,Object>(args);body.put("agentId",request.agentRef());body.put("topK",topK);return send("POST","/internal/workflow-agent-executions/"+segment(request.rootInvocationId())+"/rag/"+segment(ragId)+"/search",body);}
    private JsonNode send(String method,String path,Object body) {
        try {
            var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(30)).header("Authorization",identity.authorization()).header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2)throw new IllegalStateException("Workflow catalogue refused "+path+": HTTP "+response.statusCode());
            return response.body().isBlank()?mapper.createObjectNode():mapper.readTree(response.body());
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Workflow catalogue unavailable",ex);}
    }
    private String segment(String id){return URLEncoder.encode(id,java.nio.charset.StandardCharsets.UTF_8);}
}
