package io.mateu.ecdemo1.iacp.infra.in.rest;

import io.mateu.ecdemo1.iacp.application.out.workflow.*;
import io.mateu.ecdemo1.iacp.application.out.rag.RagStore;
import io.mateu.ecdemo1.iacp.application.usecases.workflow.WorkflowAgentExecutionService;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.Rag;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.*;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Authenticated metadata and execution snapshots. Never publish /internal through the gateway. */
@RestController
public class WorkflowAgentController {
    private final WorkflowAgentExecutionService execution;
    private final RagStore retrieval;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    public WorkflowAgentController(WorkflowAgentExecutionService execution, RagStore retrieval, io.micrometer.core.instrument.MeterRegistry meters) {
        this.execution = execution; this.retrieval = retrieval; this.meters = meters;
    }
    public record SnapshotResponse(String snapshotId, String digest, String catalogueRevision,
                                   String rootAgentId, Map<String, WorkflowAgentConfig> agents) {
        static SnapshotResponse of(WorkflowCatalogueStore.Snapshot s) {
            return new SnapshotResponse(s.snapshotId(), s.digest(), s.catalogueRevision(), s.rootAgentId(), s.agents());
        }
    }
    public record Authorization(String invocationId, String rootInvocationId, String agentId) {}
    public record Usage(String invocationId, String agentId, int round, int inputTokens, int outputTokens) {}
    public record Search(String agentId, String query, Integer topK) {}
    @GetMapping("/internal/workflow-agents")
    public List<WorkflowAgentExecutionService.Metadata> agents(HttpServletRequest http) { actor(http); return execution.catalogue(); }
    @PostMapping("/internal/workflow-agent-executions/resolve")
    public SnapshotResponse resolve(@RequestBody WorkflowAgentExecutionService.Resolve request, HttpServletRequest http) {
        return SnapshotResponse.of(execution.resolve(request, actor(http)));
    }
    @GetMapping("/internal/workflow-agent-executions/{invocationId}/snapshot")
    public SnapshotResponse snapshot(@PathVariable String invocationId, HttpServletRequest http) {
        return SnapshotResponse.of(execution.snapshot(invocationId, actor(http)));
    }
    @PostMapping("/internal/workflow-agent-executions/authorize")
    public Map<String, Boolean> authorize(@RequestBody Authorization request, HttpServletRequest http) {
        if (request.invocationId() == null || request.invocationId().isBlank())
            throw new IllegalArgumentException("invocationId is required");
        execution.authorize(request.rootInvocationId(), request.agentId(), actor(http));
        return Map.of("allowed", true);
    }
    @PostMapping("/internal/workflow-agent-executions/{rootInvocationId}/usage")
    public Map<String, Boolean> usage(@PathVariable String rootInvocationId, @RequestBody Usage request, HttpServletRequest http) {
        boolean recorded = execution.usage(rootInvocationId, request.invocationId(), request.agentId(),
                request.round(), request.inputTokens(), request.outputTokens(), actor(http));
        if (recorded) {
            var agent = execution.snapshot(rootInvocationId, actor(http)).agents().get(request.agentId());
            meters.counter("ia.tokens", "agent", agent.agentId(), "llm", agent.llm().id(), "type", "input").increment(request.inputTokens());
            meters.counter("ia.tokens", "agent", agent.agentId(), "llm", agent.llm().id(), "type", "output").increment(request.outputTokens());
        }
        return Map.of("recorded", recorded);
    }
    @PostMapping("/internal/workflow-agent-executions/{rootInvocationId}/rag/{ragId}/search")
    public RagSearchController.SearchResponse search(@PathVariable String rootInvocationId, @PathVariable String ragId,
                                                      @RequestBody Search request, HttpServletRequest http) {
        var snapshot = execution.snapshot(rootInvocationId, actor(http));
        var agent = snapshot.agents().get(request.agentId());
        if (agent == null || agent.rags().stream().noneMatch(r -> r.id().equals(ragId)))
            throw new IllegalArgumentException("RAG source is outside this agent's frozen capabilities");
        var source = snapshot.retrieval().get(ragId);
        if (source == null || request.query() == null || request.query().isBlank()
                || request.topK() != null && request.topK() < 1)
            throw new IllegalArgumentException("A valid frozen source, query and topK are required");
        var rag = Rag.of(new RagId(source.id()), new Name(source.name()), RagKind.valueOf(source.kind()),
                source.connectionUrl(), source.collection(), new LlmId(source.embeddingLlmId()), source.topK(), source.description());
        var embedding = new RagStore.EmbeddingSpec(source.embeddingModel(), source.embeddingBaseUrl(), source.embeddingApiKey());
        var chunks = retrieval.search(rag, embedding, request.query(), Math.min(20, request.topK() == null ? source.topK() : request.topK()));
        return new RagSearchController.SearchResponse(ragId, chunks.size(), chunks.stream()
                .map(c -> new RagSearchController.Passage(c.text(), c.score(), c.source())).toList());
    }
    private static String actor(HttpServletRequest request) {
        var actor = request.getAttribute(WorkflowJwtAuthenticationFilter.ACTOR_ATTRIBUTE);
        if (!(actor instanceof String id)) throw new IllegalArgumentException("Authenticated workflow identity required");
        return id;
    }
    @ExceptionHandler({IllegalArgumentException.class, RagStore.UnsupportedStoreException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException exception) {
        return ResponseEntity.status(409).body(Map.of("reason", exception.getMessage()));
    }
}
