package io.mateu.ecdemo1.iacp.application.usecases.agent;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import io.mateu.ecdemo1.iacp.application.out.repository.AgentRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.McpRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.RagRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.Agent;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.AgentId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.Llm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a stored agent into the configuration a running service can act on.
 *
 * <p>This is the one place in the service that decrypts anything, and the one place that reads
 * across all four catalogues. Both facts are on purpose: everything else in this application deals
 * in ids and booleans, so there is a single method to audit when the question is "what could
 * possibly expose a key".
 *
 * <p><strong>It drops rather than fails.</strong> An agent composed months ago may name an MCP
 * server that has since been disabled, or an LLM whose key was never filled in. The choice is
 * between refusing to serve the agent at all and serving it with what is still usable, and the
 * second is right for a chat panel: losing one tool degrades an answer, while refusing the whole
 * configuration takes the panel down. What was dropped is returned alongside the config —
 * {@link Resolved#warnings()} — so the caller can say so instead of silently being less capable
 * than its catalogue claims. A missing or unusable <em>LLM</em> is the exception, because there is
 * no degraded mode without a model: that one is a failure, and it names which of the three reasons
 * it was — disabled, an unsupported provider, or no credential.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResolveAgentConfigUseCase {

    final AgentRepository agentRepository;
    final LlmRepository llmRepository;
    final McpRepository mcpRepository;
    final RagRepository ragRepository;
    final SecretCipher cipher;

    /**
     * Where the agents answer A2A calls. One address for all of them: every ia-agent pod serves any
     * agent by id at {@code /a2a/{agentId}}, so a peer's URL is this plus its id — never a column
     * someone has to keep in step with a deployment.
     */
    @Value("${cp.a2a-base-url:http://ia-agent:8095}")
    String a2aBaseUrl = "http://ia-agent:8095";

    /** An LLM with its key in the clear. Never leaves this service except to the config endpoint. */
    public record ResolvedLlm(String id, String name, String provider, String model,
                              String baseUrl, Double temperature, Integer maxTokens,
                              String apiKey) {
    }

    public record ResolvedMcp(String id, String name, String url, String transport,
                              long timeoutSeconds) {
    }

    /**
     * {@code description} is not decoration: the agent uses it as the tool description the model
     * reads when deciding whether this source is worth searching. A catalogue entry with a vague
     * one produces a tool the model never calls.
     */
    public record ResolvedRag(String id, String name, String kind, String connectionUrl,
                              String collection, String embeddingModel, int topK,
                              String description) {
    }

    /**
     * Another agent this one may call over A2A. {@code description} is what the calling model
     * reads to decide whether to delegate — as with a RAG source, a vague one is a tool never used.
     */
    public record ResolvedPeer(String id, String name, String description, String a2aUrl) {
    }

    /** A guardrail agent a route puts in front of or behind the agent it picks. */
    public record ResolvedGuardrail(String id, String name, String a2aUrl) {
    }

    /**
     * The guardrails of the route that picked the agent, in the order they run. {@code failure} is
     * {@code CLOSED} (an unreachable or unreadable guardrail blocks) or {@code OPEN} (it lets the
     * text through, with a warning). Empty — never null — when no route with guardrails matched.
     */
    public record ResolvedGuardrails(List<ResolvedGuardrail> input, List<ResolvedGuardrail> output,
                                     String failure) {
        public static ResolvedGuardrails none() {
            return new ResolvedGuardrails(List.of(), List.of(), "CLOSED");
        }
    }

    public record Resolved(String agentId, String agentName, String agentDescription,
                           String systemPrompt, ResolvedLlm llm, List<ResolvedMcp> mcps,
                           List<ResolvedRag> rags, List<ResolvedPeer> peers,
                           ResolvedGuardrails guardrails, List<String> warnings, Map<String, Object> outputSchema) {
        public Resolved(String agentId, String agentName, String agentDescription, String systemPrompt,
                        ResolvedLlm llm, List<ResolvedMcp> mcps, List<ResolvedRag> rags, List<ResolvedPeer> peers,
                        ResolvedGuardrails guardrails, List<String> warnings) {
            this(agentId, agentName, agentDescription, systemPrompt, llm, mcps, rags, peers, guardrails, warnings, null);
        }

        /** The same configuration, with the guardrails of the route that chose it. */
        public Resolved withGuardrails(ResolvedGuardrails guardrails, List<String> moreWarnings) {
            var all = new ArrayList<>(warnings);
            all.addAll(moreWarnings);
            return new Resolved(agentId, agentName, agentDescription, systemPrompt, llm, mcps, rags,
                    peers, guardrails, List.copyOf(all), outputSchema);
        }
    }

    public static class AgentNotUsableException extends RuntimeException {
        public AgentNotUsableException(String message) { super(message); }
    }

    @Transactional(readOnly = true)
    public Resolved handle(String agentId) {
        Agent agent = agentRepository.findById(new AgentId(agentId))
                .orElseThrow(() -> new AgentNotUsableException("No agent with id '" + agentId + "'"));
        if (!agent.isUsable()) {
            throw new AgentNotUsableException("Agent '" + agentId + "' is disabled");
        }

        var warnings = new ArrayList<String>();

        Llm llm = llmRepository.findById(agent.getLlmId())
                .orElseThrow(() -> new AgentNotUsableException(
                        "Agent '" + agentId + "' names LLM '" + agent.getLlmId()
                                + "', which is not in the catalogue"));
        var usability = llm.usability();
        if (!usability.isUsable()) {
            // Three different operator mistakes and three different fixes, so the message names
            // the one that applies. It used to name only two, and an unsupported provider came
            // out disguised as a missing credential — sending whoever read it to paste a key that
            // could not have helped.
            throw new AgentNotUsableException("Agent '" + agentId + "' names LLM '"
                    + llm.getId() + "' (" + llm.getProvider() + "), which is "
                    + usability.label() + ".");
        }

        var mcps = new ArrayList<ResolvedMcp>();
        for (var mcpId : agent.getMcpIds()) {
            var found = mcpRepository.findById(mcpId);
            if (found.isEmpty()) {
                warnings.add("MCP '" + mcpId + "' is no longer in the catalogue — skipped");
                continue;
            }
            var mcp = found.get();
            if (!mcp.isUsable()) {
                warnings.add("MCP '" + mcp.getName() + "' is disabled — skipped");
                continue;
            }
            mcps.add(new ResolvedMcp(mcp.getId().value(), mcp.getName().value(),
                    mcp.getEndpoint().value(), mcp.getTransport().name(),
                    mcp.getTimeout().toSeconds()));
        }

        var rags = new ArrayList<ResolvedRag>();
        for (var ragId : agent.getRagIds()) {
            var found = ragRepository.findById(ragId);
            if (found.isEmpty()) {
                warnings.add("RAG '" + ragId + "' is no longer in the catalogue — skipped");
                continue;
            }
            var rag = found.get();
            if (!rag.isUsable()) {
                warnings.add("RAG '" + rag.getName() + "' is disabled — skipped");
                continue;
            }
            // The embedding model is resolved to its name and not its key, and now for a
            // concrete reason rather than a general one: the caller never embeds anything. It
            // turns each of these into a tool that posts a question to /internal/rag/{id}/search,
            // and the embedding happens on this side, where the credential already is. The name
            // is here so a resolved configuration says what a source was embedded with — which
            // is the thing that makes a collection queryable or not.
            var embeddingModel = llmRepository.findById(rag.getEmbeddingLlmId())
                    .map(e -> e.getModel().value())
                    .orElse(null);
            if (embeddingModel == null) {
                warnings.add("RAG '" + rag.getName() + "' names embedding LLM '"
                        + rag.getEmbeddingLlmId() + "', which is not in the catalogue — skipped");
                continue;
            }
            rags.add(new ResolvedRag(rag.getId().value(), rag.getName().value(),
                    rag.getKind().name(), rag.getConnectionUrl(), rag.getCollection(),
                    embeddingModel, rag.getTopK(), rag.getDescription()));
        }

        var peers = new ArrayList<ResolvedPeer>();
        for (var peerId : agent.getPeerAgentIds()) {
            var found = agentRepository.findById(peerId);
            if (found.isEmpty()) {
                warnings.add("Peer agent '" + peerId + "' is no longer in the catalogue — skipped");
                continue;
            }
            var peer = found.get();
            if (!peer.isUsable()) {
                warnings.add("Peer agent '" + peer.getName() + "' is disabled — skipped");
                continue;
            }
            peers.add(new ResolvedPeer(peer.getId().value(), peer.getName().value(),
                    peer.getDescription(), a2aUrl(peer.getId().value())));
        }

        if (!warnings.isEmpty()) {
            log.warn("Agent {} resolved with {} warning(s): {}", agentId, warnings.size(), warnings);
        }

        return new Resolved(
                agent.getId().value(),
                agent.getName().value(),
                agent.getDescription(),
                agent.getSystemPrompt().value(),
                new ResolvedLlm(llm.getId().value(), llm.getName().value(),
                        llm.getProvider().name(), llm.getModel().value(), llm.getBaseUrl(),
                        llm.getSampling().temperature(), llm.getSampling().maxTokens(),
                        cipher.decrypt(llm.getCredential().cipherText())),
                mcps, rags, peers, ResolvedGuardrails.none(), warnings, agent.getOutputSchema());
    }

    /** The A2A endpoint an agent answers at. */
    public String a2aUrl(String agentId) {
        return a2aBaseUrl.replaceAll("/+$", "") + "/a2a/" + agentId;
    }
}
