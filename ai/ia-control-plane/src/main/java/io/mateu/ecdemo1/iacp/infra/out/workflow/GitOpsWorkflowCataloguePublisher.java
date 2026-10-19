package io.mateu.ecdemo1.iacp.infra.out.workflow;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import io.mateu.ecdemo1.iacp.application.out.gitops.*;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.*;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.application.out.workflow.*;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Publication reads capabilities from the fetched YAML, never from console-editable live rows. */
@Component
@ConditionalOnProperty(name = "cp.gitops.enabled", havingValue = "true")
public class GitOpsWorkflowCataloguePublisher implements WorkflowCataloguePublisher {
    private static final Logger LOG = LoggerFactory.getLogger(GitOpsWorkflowCataloguePublisher.class);
    private final WorkflowCatalogueStore store;
    private final GitopsManagedRegistry managed;
    private final CredentialResolver credentials;
    private final LlmRepository llms;
    private final SecretCipher cipher;
    private final String workflowUrl;
    public GitOpsWorkflowCataloguePublisher(WorkflowCatalogueStore store, GitopsManagedRegistry managed,
            CredentialResolver credentials, LlmRepository llms, SecretCipher cipher,
            @Value("${cp.workflow.a2a-base-url:http://ia-workflow-worker:8095}") String workflowUrl) {
        this.store = store; this.managed = managed; this.credentials = credentials;
        this.llms = llms; this.cipher = cipher; this.workflowUrl = workflowUrl.replaceAll("/+$", "");
    }
    @Override @Transactional
    public void publish(DesiredCatalogue desired) {
        var modelDefinitions = index(desired.llms(), LlmManifest::id);
        var mcpDefinitions = index(desired.mcps(), McpManifest::id);
        var ragDefinitions = index(desired.rags(), RagManifest::id);
        var agentDefinitions = index(desired.agents(), AgentManifest::id);
        var configurations = new TreeMap<String, WorkflowAgentConfig>();
        var retrieval = new TreeMap<String, WorkflowCatalogueStore.FrozenRag>();
        for (var manifest : desired.agents()) {
            if (!managed.isManaged("agent", manifest.id()) || manifest.outputSchema() == null || !enabled(manifest.enabled())) continue;
            try {
                WorkflowOutputSchemas.validate(manifest.outputSchema());
                var llm = model(required(modelDefinitions, "llm", manifest.llm()));
                var tools = new ArrayList<WorkflowAgentConfig.Mcp>();
                for (var id : ids(manifest.mcp())) {
                    var m = required(mcpDefinitions, "mcp", id);
                    if (!enabled(m.enabled()) || m.url() == null || m.transport() == null)
                        throw new IllegalArgumentException("MCP dependency is unavailable");
                    tools.add(new WorkflowAgentConfig.Mcp(id, name(m.name(), id), m.url(), m.transport().name(),
                            m.timeoutSeconds() == null ? 60 : m.timeoutSeconds()));
                }
                var sources = new ArrayList<WorkflowAgentConfig.Rag>();
                var localRetrieval = new TreeMap<String, WorkflowCatalogueStore.FrozenRag>();
                for (var id : ids(manifest.rag())) {
                    var r = required(ragDefinitions, "rag", id);
                    var embedding = model(required(modelDefinitions, "llm", r.embeddingLlmId()));
                    var connection = io.mateu.ecdemo1.iacp.application.usecases.workflow.ResolveRagConnection.resolve(r, credentials);
                    if (!enabled(r.enabled()) || !Set.of("OPENAI", "OPENAI_COMPATIBLE").contains(embedding.provider())
                            || r.store() == null || !"PGVECTOR".equals(r.store().name())
                            || connection.isBlank())
                        throw new IllegalArgumentException("Retrieval dependency is unavailable");
                    int topK = r.topK() == null ? 5 : r.topK();
                    sources.add(new WorkflowAgentConfig.Rag(id, name(r.name(), id), r.store().name(),
                            connection, r.collection(), embedding.model(), topK, r.description()));
                    localRetrieval.put(id, new WorkflowCatalogueStore.FrozenRag(id, name(r.name(), id), r.store().name(),
                            connection, r.collection(), embedding.id(), embedding.model(), embedding.baseUrl(),
                            embedding.apiKey(), topK, r.description()));
                }
                var peers = new ArrayList<WorkflowAgentConfig.Peer>();
                for (var id : ids(manifest.peers())) {
                    var p = required(agentDefinitions, "agent", id);
                    if (!enabled(p.enabled()) || p.outputSchema() == null)
                        throw new IllegalArgumentException("Workflow peer is unavailable");
                    peers.add(new WorkflowAgentConfig.Peer(id, name(p.name(), id), p.description(), workflowUrl + "/a2a/" + id));
                }
                var config = new WorkflowAgentConfig(manifest.id(), name(manifest.name(), manifest.id()), manifest.description(),
                        manifest.systemPrompt() == null ? "" : manifest.systemPrompt(), llm, tools, sources, peers, manifest.outputSchema());
                configurations.put(config.agentId(), config);
                retrieval.putAll(localRetrieval);
            } catch (IllegalArgumentException | NoSuchElementException e) {
                // Log identity only: exceptions from downstream configuration must not reveal keys.
                LOG.warn("GitOps agent {} is not usable for workflow publication", manifest.id());
            }
        }
        String revision = store.digest(List.of(configurations, retrieval));
        store.publish(new WorkflowCatalogueStore.Publication(revision, configurations, retrieval));
    }
    private WorkflowAgentConfig.Llm model(LlmManifest m) {
        if (!enabled(m.enabled()) || m.provider() == null || !m.provider().isApiKeyBased()
                || m.model() == null || m.model().isBlank()) throw new IllegalArgumentException("Model dependency is unavailable");
        // Only credentials may come from the protected live store. All capabilities, prompts,
        // URLs, sampling, and model names always come from this exact fetched GitOps publication.
        var key = m.credentialEnv() == null || m.credentialEnv().isBlank() ? null : credentials.resolve(m.credentialEnv());
        if (key == null || key.isBlank()) key = llms.findById(new LlmId(m.id()))
                .map(llm -> cipher.decrypt(llm.getCredential().cipherText())).orElse(null);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Model credential is unavailable");
        return new WorkflowAgentConfig.Llm(m.id(), name(m.name(), m.id()), m.provider().name(), m.model(),
                m.baseUrl(), m.temperature(), m.maxTokens(), key);
    }
    private <T> T required(Map<String, T> entries, String kind, String id) {
        var entry = entries.get(id);
        if (entry == null || !managed.isManaged(kind, id)) throw new IllegalArgumentException("Dependency is not published by GitOps");
        return entry;
    }
    private static boolean enabled(Boolean value) { return value == null || value; }
    private static String name(String value, String id) { return value == null || value.isBlank() ? id : value; }
    private static List<String> ids(List<String> value) { return value == null ? List.of() : value.stream().distinct().toList(); }
    private static <T> Map<String, T> index(List<T> values, Function<T, String> id) {
        return values.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
