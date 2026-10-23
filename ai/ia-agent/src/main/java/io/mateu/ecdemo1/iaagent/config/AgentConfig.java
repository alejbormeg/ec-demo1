package io.mateu.ecdemo1.iaagent.config;

import java.util.List;

/**
 * One agent's configuration, as the control plane resolved it.
 *
 * <p>The shape mirrors {@code ResolveAgentConfigUseCase.Resolved} on the other side. It is
 * restated here rather than shared through a jar on purpose: this is a wire contract between two
 * independently deployed services, and a shared class would make every field change a lockstep
 * release of both. Unknown fields are ignored on the way in, so the control plane can add one
 * without this service noticing.
 *
 * <p>{@code llm.apiKey} arrives in the clear. It is the reason the endpoint that serves this has
 * no gateway route.
 */
public record AgentConfig(
        String agentId,
        String agentName,
        String agentDescription,
        String systemPrompt,
        Llm llm,
        List<Mcp> mcps,
        List<Rag> rags,
        List<Peer> peers,
        Guardrails guardrails,
        List<String> warnings) {

    public record Llm(String id, String name, String provider, String model, String baseUrl,
                      Double temperature, Integer maxTokens, String apiKey) {
    }

    /** {@code toolErrors}: RETURN_TO_MODEL lets workflow agents retry effectful isError results; null/FAIL holds them for review. */
    public record Mcp(String id, String name, String url, String transport, long timeoutSeconds, String toolErrors) {
        public boolean returnToolErrorsToModel() { return "RETURN_TO_MODEL".equalsIgnoreCase(toolErrors); }
    }

    /** {@code description} becomes the tool description the model reads. See RagToolFactory. */
    public record Rag(String id, String name, String kind, String connectionUrl, String collection,
                      String embeddingModel, int topK, String description) {
    }

    /**
     * Another agent this one may call over A2A, at {@code a2aUrl}. {@code description} becomes the
     * tool description the model reads — see {@code PeerToolFactory}.
     */
    public record Peer(String id, String name, String description, String a2aUrl) {
    }

    /** A guardrail agent, called over A2A at {@code a2aUrl}. */
    public record Guardrail(String id, String name, String a2aUrl) {
    }

    /**
     * The guardrails of the route that picked this agent, in the order they run, and what a
     * guardrail that cannot give a verdict amounts to: {@code CLOSED} blocks, {@code OPEN} passes.
     */
    public record Guardrails(List<Guardrail> input, List<Guardrail> output, String failure) {
        public Guardrails {
            input = input == null ? List.of() : input;
            output = output == null ? List.of() : output;
        }

        public static Guardrails none() {
            return new Guardrails(List.of(), List.of(), "CLOSED");
        }

        public boolean failOpen() {
            return "OPEN".equalsIgnoreCase(failure);
        }
    }

    /** Null from /config and from a control plane that predates guardrails; none here. */
    public Guardrails guardrailsOrNone() {
        return guardrails == null ? Guardrails.none() : guardrails;
    }

    public List<String> mcpUrls() {
        return mcps == null ? List.of() : mcps.stream().map(Mcp::url).toList();
    }

    /** Null from a control plane that predates A2A; empty here. */
    public List<Peer> peersOrEmpty() {
        return peers == null ? List.of() : peers;
    }
}
