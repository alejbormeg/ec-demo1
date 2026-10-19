package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowAgentRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Profile("workflow")
public class WorkflowExecutionController {
    private final WorkflowIdentity identity;private final WorkflowAgentRunner runner;private final ObjectMapper mapper;
    public WorkflowExecutionController(WorkflowIdentity identity,WorkflowAgentRunner runner,ObjectMapper mapper){this.identity=identity;this.runner=runner;this.mapper=mapper;}
    @GetMapping(value="/internal/workflow-agent-executions",produces=MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String,Object>> list(@RequestHeader("Authorization")String token){identity.authenticate(token,true);return runner.list().stream().map(this::summary).toList();}
    private Map<String,Object> summary(io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution e){
        var row=new LinkedHashMap<String,Object>();row.put("id",e.request.invocationId());row.put("rootId",e.request.rootInvocationId());row.put("agentId",e.request.agentRef());row.put("state",e.status.name());
        row.put("deadline",java.time.Instant.ofEpochMilli(e.effectiveDeadline).toString());row.put("inputTokens",e.rounds.stream().mapToInt(r->r.inputTokens()).sum());row.put("outputTokens",e.rounds.stream().mapToInt(r->r.outputTokens()).sum());
        row.put("traces",e.rounds.stream().map(r->r.metadata().get("trace")).filter(Objects::nonNull).toList());row.put("requestDigest",e.digest);row.put("snapshotDigest",e.snapshotDigest);row.put("catalogueRevision",e.catalogueRevision);row.put("error",e.error);
        row.put("unknownCalls",e.tools.stream().filter(t->t.status==io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.ToolStatus.REVIEW_REQUIRED).map(t->Map.of("toolCallId",t.id,"toolName",t.name,"toolDigest",t.digest,"arguments",t.arguments)).toList());return row;
    }
    @GetMapping(value="/internal/workflow-agent-executions/{id}",produces=MediaType.APPLICATION_JSON_VALUE)
    public String execution(@PathVariable String id,@RequestHeader("Authorization")String token)throws Exception{
        identity.authenticate(token,true);return mapper.writeValueAsString(Map.of("execution",runner.get(id),"chain",runner.chain(runner.get(id).request.rootInvocationId())));
    }
    @PostMapping(value="/internal/workflow-agent-executions/{id}/review",produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,String> review(@PathVariable String id,@RequestHeader("Authorization")String token,@RequestBody Map<String,String> body){
        var operator=identity.authenticate(token,true);if(!Objects.equals(body.get("requestDigest"),runner.get(id).digest))throw new IllegalArgumentException("Request digest changed");runner.reconcile(id,body.get("toolCallId"),body.get("toolDigest"),body.get("confirmedResult"),body.get("evidence"),operator);return Map.of("status","QUEUED");
    }
    @PostMapping(value="/internal/workflow-agent-executions/{id}/cancel",produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,String> cancel(@PathVariable String id,@RequestHeader("Authorization")String token){identity.authenticate(token,true);runner.cancel(runner.get(id).request.rootInvocationId());return Map.of("status","CANCELLATION_REQUESTED");}
    @ExceptionHandler(SecurityException.class)ResponseEntity<Map<String,String>> unauthorized(SecurityException ex){return ResponseEntity.status(403).body(Map.of("error",ex.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class)ResponseEntity<Map<String,String>> invalid(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",Objects.toString(ex.getMessage(),"Invalid request")));}
    @ExceptionHandler(IllegalStateException.class)ResponseEntity<Map<String,String>> conflict(IllegalStateException ex){return ResponseEntity.status(409).body(Map.of("error",Objects.toString(ex.getMessage(),"Conflict")));}
}
