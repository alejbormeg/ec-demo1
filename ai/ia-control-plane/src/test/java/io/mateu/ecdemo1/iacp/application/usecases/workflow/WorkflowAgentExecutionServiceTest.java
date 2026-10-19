package io.mateu.ecdemo1.iacp.application.usecases.workflow;

import io.mateu.ecdemo1.iacp.application.out.workflow.*;
import io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowAgentExecutionServiceTest {
    public static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of("decision", Map.of("type", "string")));
    static class Store implements WorkflowCatalogueStore {
        Publication publication;
        final Map<String, Snapshot> snapshots = new HashMap<>();
        final Map<String, String> receipts = new HashMap<>();
        @Override public Optional<Publication> publication() { return Optional.ofNullable(publication); }
        @Override public void publish(Publication value) { publication = value; }
        @Override public Optional<Snapshot> find(String id) { return Optional.ofNullable(snapshots.get(id)); }
        @Override public Snapshot freeze(Snapshot value) { return snapshots.computeIfAbsent(value.snapshotId(), k -> value); }
        @Override public String digest(Object value) { return JsonDocuments.write(value); }
        @Override public boolean recordUsage(String id, String digest, String a, String l, String m, int i, int o, String actor, String inv) {
            var previous = receipts.putIfAbsent(id, digest);
            if (previous != null && !previous.equals(digest)) throw new IllegalArgumentException("Changed usage");
            return previous == null;
        }
    }
    static WorkflowAgentConfig config(String id, List<String> peers) {
        return new WorkflowAgentConfig(id, id, "Synthetic", "Published prompt", new WorkflowAgentConfig.Llm(
                "model", "Model", "OPENAI_COMPATIBLE", "synthetic-model", "http://synthetic/v1", 0.0, 100, "synthetic-key"),
                List.of(new WorkflowAgentConfig.Mcp("lookup", "Lookup", "http://lookup/mcp", "STREAMABLE_HTTP", 10),
                        new WorkflowAgentConfig.Mcp("update", "Update", "http://update/mcp", "STREAMABLE_HTTP", 10)),
                List.of(), peers.stream().map(p -> new WorkflowAgentConfig.Peer(p, p, "Synthetic", "http://worker/a2a/" + p)).toList(), SCHEMA);
    }
    static Store store(WorkflowAgentConfig... agents) {
        var store = new Store(); var all = new TreeMap<String, WorkflowAgentConfig>();
        for (var agent : agents) all.put(agent.agentId(), agent);
        store.publish(new WorkflowCatalogueStore.Publication("revision-1", all, Map.of()));
        return store;
    }
    static WorkflowAgentExecutionService service(Store store) { return new WorkflowAgentExecutionService(store, (a, l, actor) -> {}); }
    static WorkflowAgentExecutionService.Resolve request(List<String> tools, List<String> peers) {
        return new WorkflowAgentExecutionService.Resolve("invocation", "root", "input-digest", tools, null, peers);
    }
    @Test void subsetsRestrictOnlyRootAndKeepPublishedModel() {
        var service = service(store(config("root", List.of("child")), config("child", List.of())));
        var snapshot = service.resolve(request(List.of("lookup"), null), "service-account");
        assertEquals(List.of("lookup"), snapshot.agents().get("root").mcps().stream().map(WorkflowAgentConfig.Mcp::id).toList());
        assertEquals(2, snapshot.agents().get("child").mcps().size());
        assertEquals("synthetic-model", snapshot.agents().get("root").llm().model());
        assertThrows(IllegalArgumentException.class, () -> service(store(config("root", List.of())))
                .resolve(request(List.of("unpublished"), null), "service-account"));
    }
    @Test void omittedInheritsAndEmptyDisables() {
        var inherited = service(store(config("root", List.of("child")), config("child", List.of())))
                .resolve(request(null, null), "service-account");
        assertEquals(2, inherited.agents().size());
        var restricted = service(store(config("root", List.of("child")), config("child", List.of())))
                .resolve(request(List.of(), List.of()), "service-account");
        assertEquals(1, restricted.agents().size()); assertEquals(List.of(), restricted.agents().get("root").mcps());
    }
    @Test void restartAndCatalogueChangesKeepOriginalSnapshot() {
        var store = store(config("root", List.of()));
        var first = service(store).resolve(request(null, null), "service-account");
        store.publish(new WorkflowCatalogueStore.Publication("revision-2", Map.of(), Map.of()));
        var replay = service(store).resolve(request(null, null), "service-account");
        assertEquals(first, replay); assertEquals("revision-1", replay.catalogueRevision());
        assertThrows(IllegalArgumentException.class, () -> service(store).resolve(request(List.of(), null), "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service(store).resolve(request(null, null), "another-account"));
    }
    @Test void catalogueNeverContainsCredentials() {
        var catalogue = service(store(config("root", List.of()))).catalogue();
        assertEquals(1, catalogue.size());
        assertFalse(JsonDocuments.write(catalogue).contains("synthetic-key"));
        assertFalse(JsonDocuments.write(catalogue).contains("systemPrompt"));
    }
    @Test void unknownAndUnpublishedPeerFailWithoutSilentDegradation() {
        var service = service(store(config("root", List.of("missing"))));
        assertEquals(List.of(), service.catalogue());
        assertThrows(IllegalArgumentException.class, () -> service.resolve(request(null, List.of()), "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service(store()).resolve(request(null, null), "service-account"));
    }
    @Test void cyclesAndDepthLimitAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service(store(config("root", List.of("child")), config("child", List.of("root"))))
                .resolve(request(null, null), "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service(store(config("root", List.of("child")), config("child", List.of("leaf")),
                config("leaf", List.of("too-deep")), config("too-deep", List.of()))).resolve(request(null, null), "service-account"));
        assertEquals(3, service(store(config("root", List.of("child")), config("child", List.of("leaf")), config("leaf", List.of())))
                .resolve(request(null, null), "service-account").agents().size());
    }
    @Test void allPeersMustHaveOutputSchemas() {
        var child = config("child", List.of());
        var untyped = new WorkflowAgentConfig(child.agentId(), child.agentName(), child.agentDescription(), child.systemPrompt(),
                child.llm(), child.mcps(), child.rags(), child.peers(), null);
        var service = service(store(config("root", List.of("child")), untyped));
        assertEquals(List.of(), service.catalogue());
        assertThrows(IllegalArgumentException.class, () -> service.resolve(request(null, null), "service-account"));
    }
    @Test void checksEveryEffectiveAgentBudgetAndExactIdentity() {
        var checked = new ArrayList<String>();
        var store = store(config("root", List.of("child")), config("child", List.of()));
        var service = new WorkflowAgentExecutionService(store, (a, l, actor) -> checked.add(a + ":" + actor));
        service.resolve(request(null, null), "service-account");
        assertTrue(checked.containsAll(List.of("root:service-account", "child:service-account")));
        assertThrows(IllegalArgumentException.class, () -> service.authorize("invocation", "outside", "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service.snapshot("invocation", "human-user"));
        var denied = new WorkflowAgentExecutionService(store(config("root", List.of())), (a, l, actor) -> { throw new IllegalArgumentException("Budget spent"); });
        assertThrows(IllegalArgumentException.class, () -> denied.resolve(request(null, null), "service-account"));
    }
    @Test void usageIsIdempotentPerChildInvocationAndRoundAndRejectsChangedValues() {
        var store = store(config("root", List.of("child")), config("child", List.of()));
        var service = service(store); service.resolve(request(null, null), "service-account");
        assertTrue(service.usage("invocation", "invocation", "root", 0, 4, 2, "service-account"));
        assertFalse(service.usage("invocation", "invocation", "root", 0, 4, 2, "service-account"));
        assertTrue(service.usage("invocation", "child-invocation", "child", 0, 4, 2, "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service.usage("invocation", "invocation", "root", 0, 5, 2, "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service.usage("invocation", "unknown", "outside", 0, 4, 2, "service-account"));
        assertThrows(IllegalArgumentException.class, () -> service.usage("invocation", "unknown", "root", 0, -1, 2, "service-account"));
    }
}
