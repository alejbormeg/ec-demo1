package io.mateu.ecdemo1.iacp.application.usecases.workflow;

import io.mateu.ecdemo1.iacp.application.out.workflow.WorkflowAgentConfig;
import io.mateu.ecdemo1.iacp.application.out.workflow.WorkflowCatalogueStore;
import io.mateu.ecdemo1.iacp.application.out.workflow.WorkflowBudgetPolicy;
import java.util.*;

/** Workflow semantics, independent from HTTP, persistence and model SDKs. */
public final class WorkflowAgentExecutionService {
    private final WorkflowCatalogueStore store;
    private final WorkflowBudgetPolicy budgets;
    public WorkflowAgentExecutionService(WorkflowCatalogueStore store, WorkflowBudgetPolicy budgets) {
        this.store = store; this.budgets = budgets;
    }
    public record Resolve(String invocationId, String agentId, String requestDigest,
                          List<String> mcp, List<String> rag, List<String> peers) {}
    public record Metadata(String id, String name, String description, List<String> mcp,
                           List<String> rag, List<String> peers, Map<String, Object> outputSchema,
                           String revision) {}
    public List<Metadata> catalogue() {
        var published = store.publication();
        if (published.isEmpty()) return List.of();
        var p = published.get();
        return p.agents().values().stream().filter(a -> validRoot(a, p.agents()))
                .sorted(Comparator.comparing(WorkflowAgentConfig::agentId))
                .map(a -> new Metadata(a.agentId(), a.agentName(), a.agentDescription(),
                        a.mcps().stream().map(WorkflowAgentConfig.Mcp::id).toList(),
                        a.rags().stream().map(WorkflowAgentConfig.Rag::id).toList(),
                        a.peers().stream().map(WorkflowAgentConfig.Peer::id).toList(),
                        a.outputSchema(), p.revision())).toList();
    }
    private boolean validRoot(WorkflowAgentConfig root, Map<String, WorkflowAgentConfig> all) {
        try { graph(root, all); return true; } catch (IllegalArgumentException e) { return false; }
    }
    public WorkflowCatalogueStore.Snapshot resolve(Resolve request, String actor) {
        required(request.invocationId(), "invocationId"); required(request.agentId(), "agentId");
        required(request.requestDigest(), "requestDigest"); required(actor, "technicalActor");
        String selection = store.digest(request);
        var existing = store.find(request.invocationId());
        if (existing.isPresent()) return same(existing.get(), request, selection, actor);
        var publication = store.publication().orElseThrow(() -> new IllegalArgumentException("No successful workflow catalogue publication"));
        var root = publication.agents().get(request.agentId());
        if (root == null) throw new IllegalArgumentException("Agent is not published for workflows");
        // Validate the declared graph before applying optional restrictions: a broken published
        // dependency must never silently degrade a workflow agent.
        graph(root, publication.agents());
        root = root.restricted(request.mcp(), request.rag(), request.peers());
        var effective = graph(root, publication.agents());
        effective.values().forEach(a -> budgets.authorize(a.agentId(), a.llm().id(), actor));
        var retrieval = new TreeMap<String, WorkflowCatalogueStore.FrozenRag>();
        effective.values().forEach(a -> a.rags().forEach(r -> {
            var frozen = publication.retrieval().get(r.id());
            if (frozen == null) throw new IllegalArgumentException("Missing frozen retrieval source");
            retrieval.put(r.id(), frozen);
        }));
        var proposed = new WorkflowCatalogueStore.Snapshot(request.invocationId(), store.digest(List.of(effective, retrieval)),
                publication.revision(), request.agentId(), Collections.unmodifiableMap(effective),
                Collections.unmodifiableMap(retrieval), request.requestDigest(), selection, actor);
        return same(store.freeze(proposed), request, selection, actor);
    }
    private WorkflowCatalogueStore.Snapshot same(WorkflowCatalogueStore.Snapshot snapshot, Resolve request,
                                                String selection, String actor) {
        if (!snapshot.technicalActor().equals(actor) || !snapshot.selectionDigest().equals(selection)
                || !snapshot.requestDigest().equals(request.requestDigest()))
            throw new IllegalArgumentException("Invocation already exists with a different request or technical identity");
        return snapshot;
    }
    public WorkflowCatalogueStore.Snapshot snapshot(String invocationId, String actor) {
        var value = store.find(invocationId).orElseThrow(() -> new IllegalArgumentException("Unknown workflow agent invocation"));
        if (!value.technicalActor().equals(actor)) throw new IllegalArgumentException("Invocation belongs to a different technical identity");
        return value;
    }
    public void authorize(String rootInvocationId, String agentId, String actor) {
        var snapshot = snapshot(rootInvocationId, actor);
        var agent = snapshot.agents().get(agentId);
        if (agent == null) throw new IllegalArgumentException("Agent is outside the frozen invocation graph");
        budgets.authorize(agentId, agent.llm().id(), actor);
    }
    public boolean usage(String rootInvocationId, String invocationId, String agentId, int round,
                         int inputTokens, int outputTokens, String actor) {
        required(invocationId, "invocationId");
        if (round < 0 || inputTokens < 0 || outputTokens < 0 || (long) inputTokens + outputTokens > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid token usage");
        var snapshot = snapshot(rootInvocationId, actor);
        var a = snapshot.agents().get(agentId);
        if (a == null) throw new IllegalArgumentException("Usage agent is outside the frozen invocation graph");
        var identity = List.of(invocationId, round);
        return store.recordUsage(store.digest(identity), store.digest(List.of(rootInvocationId, invocationId,
                agentId, round, inputTokens, outputTokens)), agentId, a.llm().id(), a.llm().model(),
                inputTokens, outputTokens, actor, invocationId);
    }
    private static void required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512)
            throw new IllegalArgumentException(field + " is required and must be at most 512 characters");
    }
    private static SortedMap<String, WorkflowAgentConfig> graph(WorkflowAgentConfig root, Map<String, WorkflowAgentConfig> all) {
        var effective = new TreeMap<String, WorkflowAgentConfig>();
        visit(root, all, effective, new HashSet<>(), 0);
        return effective;
    }
    private static void visit(WorkflowAgentConfig agent, Map<String, WorkflowAgentConfig> all,
                              Map<String, WorkflowAgentConfig> effective, Set<String> path, int depth) {
        if (depth > 2) throw new IllegalArgumentException("Workflow A2A graph exceeds two hops");
        if (!path.add(agent.agentId())) throw new IllegalArgumentException("Workflow A2A cycle detected");
        if (agent.outputSchema() == null || !"object".equals(agent.outputSchema().get("type")))
            throw new IllegalArgumentException("Workflow agents and peers require an object outputSchema");
        effective.putIfAbsent(agent.agentId(), agent);
        for (var peer : agent.peers()) {
            var child = all.get(peer.id());
            if (child == null) throw new IllegalArgumentException("Peer is not published for workflows: " + peer.id());
            visit(child, all, effective, path, depth + 1);
        }
        path.remove(agent.agentId());
    }
}
