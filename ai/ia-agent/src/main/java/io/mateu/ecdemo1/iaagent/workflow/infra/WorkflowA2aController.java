package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowAgentRunner;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Durable workflow A2A profile. Delegation metadata binds to a persisted parent tool call. */
@RestController @Profile("workflow") @RequestMapping("/a2a/{agentId}")
public class WorkflowA2aController {
    private final WorkflowIdentity identity;private final WorkflowAgentRunner runner;private final ObjectMapper mapper;
    public WorkflowA2aController(WorkflowIdentity identity,WorkflowAgentRunner runner,ObjectMapper mapper){this.identity=identity;this.runner=runner;this.mapper=mapper;}
    @PostMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    public String rpc(@PathVariable String agentId,@RequestHeader("Authorization")String token,@RequestBody String body)throws Exception {
        identity.authenticate(token,false);var json=mapper.readTree(body);Object id=mapper.convertValue(json.path("id"),Object.class);
        try {
            var params=json.path("params");WorkflowExecution execution;
            switch(json.path("method").asText()) {
                case "tasks/get" -> execution=runner.get(params.path("id").asText());
                case "tasks/cancel" -> {execution=runner.get(params.path("id").asText());if(!agentId.equals(execution.request.agentRef()))throw new IllegalArgumentException("Agent mismatch");runner.cancel(execution.request.rootInvocationId());execution=runner.get(execution.request.invocationId());}
                case "message/send" -> {
                    var metadata=params.path("metadata").path("workflow");
                    var parts=params.path("message").path("parts");if(parts.size()!=1||!"data".equals(parts.get(0).path("kind").asText()))throw new IllegalArgumentException("Workflow A2A requires one JSON data part");
                    execution=runner.acceptDelegation(agentId,metadata.path("parentInvocationId").asText(),metadata.path("toolCallId").asText(),mapper.convertValue(parts.get(0).path("data"),Map.class));
                }
                default -> throw new IllegalArgumentException("Supported methods: message/send, tasks/get, tasks/cancel");
            }
            if(!agentId.equals(execution.request.agentRef()))throw new IllegalArgumentException("Agent mismatch");
            if("message/send".equals(json.path("method").asText())){runner.runDelegation(execution.request.invocationId());execution=runner.get(execution.request.invocationId());}
            var result=new LinkedHashMap<String,Object>();result.put("id",execution.request.invocationId());result.put("contextId",execution.request.rootInvocationId());
            result.put("kind","task");result.put("status",Map.of("state",a2aState(execution.status),"timestamp",java.time.Instant.now().toString()));
            if(execution.result!=null)result.put("artifacts",List.of(Map.of("artifactId","result","parts",List.of(Map.of("kind","data","data",mapper.readTree(execution.result))))));
            return mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"result",result));
        }catch(IllegalArgumentException ex){return mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"error",Map.of("code",-32602,"message",ex.getMessage())));}
    }
    private String a2aState(WorkflowExecution.Status status){return switch(status){case QUEUED->"submitted";case RUNNING->"working";case REVIEW_REQUIRED->"input-required";case COMPLETED->"completed";case FAILED->"failed";case CANCELLED->"canceled";};}
    @ExceptionHandler(SecurityException.class)ResponseEntity<Map<String,String>> unauthorized(SecurityException ex){return ResponseEntity.status(403).body(Map.of("error",ex.getMessage()));}
}
