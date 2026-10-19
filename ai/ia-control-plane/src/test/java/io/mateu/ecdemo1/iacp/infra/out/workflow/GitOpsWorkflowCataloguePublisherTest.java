package io.mateu.ecdemo1.iacp.infra.out.workflow;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import io.mateu.ecdemo1.iacp.application.out.gitops.*;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.*;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.application.out.workflow.*;
import io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpTransport;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class GitOpsWorkflowCataloguePublisherTest {
    private final AtomicReference<WorkflowCatalogueStore.Publication> publication = new AtomicReference<>();
    private final Set<String> consoleOwned = new HashSet<>();
    private final GitopsManagedRegistry managed = new GitopsManagedRegistry() {
        public boolean isManaged(String kind, String id) { return !consoleOwned.contains(kind + "/" + id); }
        public Set<String> managedIds(String kind) { return Set.of(); }
        public void markManaged(String kind, String id) {}
        public void unmark(String kind, String id) {}
    };
    private final WorkflowCatalogueStore store = new WorkflowCatalogueStore() {
        public Optional<Publication> publication() { return Optional.ofNullable(publication.get()); }
        public void publish(Publication value) { publication.set(value); }
        public Optional<Snapshot> find(String id) { return Optional.empty(); }
        public Snapshot freeze(Snapshot value) { throw new UnsupportedOperationException(); }
        public String digest(Object value) {
            try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JsonDocuments.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        public boolean recordUsage(String a, String b, String c, String d, String e, int i, int o, String actor, String invocation) {
            throw new UnsupportedOperationException();
        }
    };
    private final LlmRepository llms = new io.mateu.ecdemo1.iacp.InMemoryRepositories.Llms() {
        @Override public Optional<io.mateu.ecdemo1.iacp.domain.aggregates.llm.Llm> findById(
                io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId id) {
            throw new AssertionError("Live model settings must not be read when YAML declares a credential env");
        }
    };
    private final SecretCipher cipher = io.mateu.ecdemo1.iacp.InMemoryRepositories.CIPHER;
    private GitOpsWorkflowCataloguePublisher publisher() {
        return new GitOpsWorkflowCataloguePublisher(store, managed, name -> "synthetic-key", llms, cipher,
                "http://ia-workflow-worker:8095");
    }
    private AgentManifest agent(String id, List<String> tools, List<String> peers) {
        return new AgentManifest(id, "Published " + id, "Published instructions", "model", tools, List.of(), peers,
                "Published description", true, Map.of("type", "object", "additionalProperties", false));
    }
    private DesiredCatalogue desired(List<AgentManifest> agents, List<McpManifest> tools) {
        return new DesiredCatalogue(List.of(new LlmManifest("model", "Published model", LlmProvider.OPENAI_COMPATIBLE,
                "published-model", "http://model.synthetic/v1", 0.0, 512, "SYNTHETIC_KEY", true)),
                tools, List.of(), List.of(), agents, List.of(), List.of());
    }
    @Test void publishesOnlyFetchedYamlCapabilitiesAndUsesWorkflowPeerUrls() {
        publisher().publish(desired(List.of(agent("root", List.of("lookup"), List.of("child")),
                        agent("child", List.of(), List.of())),
                List.of(new McpManifest("lookup", "Lookup", "http://lookup.synthetic/mcp", McpTransport.STREAMABLE_HTTP, 10L, "Synthetic", true))));
        var p = publication.get();
        assertEquals(Set.of("root", "child"), p.agents().keySet());
        var root = p.agents().get("root");
        assertEquals("Published instructions", root.systemPrompt());
        assertEquals("published-model", root.llm().model());
        assertEquals("http://lookup.synthetic/mcp", root.mcps().getFirst().url());
        assertEquals("http://ia-workflow-worker:8095/a2a/child", root.peers().getFirst().a2aUrl());
        // The test repository fails if live model settings are read instead of fetched YAML.
    }
    @Test void consoleOwnedAgentAndDependencyAreNotPublished() {
        consoleOwned.add("agent/console-only");
        consoleOwned.add("mcp/console-tool");
        publisher().publish(desired(List.of(agent("console-only", List.of(), List.of()),
                        agent("root", List.of("console-tool"), List.of())),
                List.of(new McpManifest("console-tool", "Tool", "http://lookup.synthetic/mcp", McpTransport.STREAMABLE_HTTP, 10L, "Synthetic", true))));
        assertTrue(publication.get().agents().isEmpty());
    }
    @Test void disabledAndMissingDependenciesRemoveAgentFromNewPublication() {
        publisher().publish(desired(List.of(agent("root", List.of("disabled"), List.of())),
                List.of(new McpManifest("disabled", "Tool", "http://lookup.synthetic/mcp", McpTransport.STREAMABLE_HTTP, 10L, "Synthetic", false))));
        assertTrue(publication.get().agents().isEmpty());
        publisher().publish(desired(List.of(agent("root", List.of(), List.of("missing-peer"))), List.of()));
        assertTrue(publication.get().agents().isEmpty());
    }
    @Test void chatAgentsWithoutSchemasRemainAbsentFromWorkflowPublication() {
        var chat = new AgentManifest("chat", "Chat", "Published prompt", "model", List.of(), List.of(), List.of(), "Chat", true);
        publisher().publish(desired(List.of(chat), List.of()));
        assertTrue(publication.get().agents().isEmpty());
    }
    @Test void snapshotRoundTripRetainsSchemasAndCredentialsWithoutPlaintextToString() {
        publisher().publish(desired(List.of(agent("root", List.of(), List.of())), List.of()));
        var json = JsonDocuments.write(publication.get());
        var restored = JsonDocuments.read(json, WorkflowCatalogueStore.Publication.class);
        assertEquals("synthetic-key", restored.agents().get("root").llm().apiKey());
        assertEquals("object", restored.agents().get("root").outputSchema().get("type"));
        assertFalse(restored.toString().contains("synthetic-key"));
        assertFalse(restored.agents().get("root").toString().contains("synthetic-key"));
        assertFalse(restored.agents().get("root").llm().toString().contains("synthetic-key"));
    }
    private DesiredCatalogue withEnvironmentRag() {
        var root = new AgentManifest("root", "Root", "Published instructions", "model", List.of(), List.of("handbook"),
                List.of(), "Synthetic", true, Map.of("type", "object"));
        var desired = desired(List.of(root), List.of());
        var rag = new RagManifest("handbook", "Handbook", io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind.PGVECTOR,
                null, "synthetic_handbook", "model", 3, "Synthetic", true, "SYNTHETIC_RAG_URL");
        return new DesiredCatalogue(desired.llms(), desired.mcps(), List.of(), List.of(rag), desired.agents(), List.of(), List.of());
    }
    @Test void secretBackedRagPublicationFreezesResolvedUrlAndEncryptedDocumentExcludesPlaintext() {
        var url = "jdbc:postgresql://synthetic/db?user=synthetic&password=synthetic-password";
        var publisher = new GitOpsWorkflowCataloguePublisher(store, managed,
                name -> "SYNTHETIC_RAG_URL".equals(name) ? url : "synthetic-key", llms, cipher, "http://worker");
        publisher.publish(withEnvironmentRag());
        assertEquals(url, publication.get().retrieval().get("handbook").connectionUrl());
        assertEquals(url, publication.get().agents().get("root").rags().getFirst().connectionUrl());
        var json = JsonDocuments.write(publication.get());
        assertFalse(json.contains("SYNTHETIC_RAG_URL"));
        var aes = new io.mateu.ecdemo1.iacp.infra.out.crypto.AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var encrypted = aes.encrypt(json);
        assertFalse(encrypted.contains(url)); assertFalse(encrypted.contains("synthetic-password"));
        assertEquals(json, aes.decrypt(encrypted));
    }
    @Test void unknownRagConnectionEnvironmentCannotPublishAnAgent() {
        var publisher = new GitOpsWorkflowCataloguePublisher(store, managed,
                name -> "SYNTHETIC_RAG_URL".equals(name) ? null : "synthetic-key", llms, cipher, "http://worker");
        publisher.publish(withEnvironmentRag());
        assertTrue(publication.get().agents().isEmpty());
    }

}
