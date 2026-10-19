package io.mateu.ecdemo1.iacp.application.out.workflow;

import java.util.List;
import java.util.Map;

/** A frozen workflow configuration. Credentials may cross only the authenticated internal API. */
public record WorkflowAgentConfig(String agentId, String agentName, String agentDescription,
        String systemPrompt, Llm llm, List<Mcp> mcps, List<Rag> rags, List<Peer> peers,
        Map<String, Object> outputSchema) {
    public record Llm(String id, String name, String provider, String model, String baseUrl,
                      Double temperature, Integer maxTokens, String apiKey) {
        @Override public String toString() { return "WorkflowLlm[" + id + "]"; }
    }
    public record Mcp(String id, String name, String url, String transport, long timeoutSeconds) {}
    public record Rag(String id, String name, String kind, String connectionUrl, String collection,
                      String embeddingModel, int topK, String description) {}
    public record Peer(String id, String name, String description, String a2aUrl) {}
    public WorkflowAgentConfig restricted(List<String> mcp, List<String> rag, List<String> peers) {
        return new WorkflowAgentConfig(agentId, agentName, agentDescription, systemPrompt, llm,
                select(mcps, mcp, Mcp::id), select(rags, rag, Rag::id),
                select(this.peers, peers, Peer::id), outputSchema);
    }
    private static <T> List<T> select(List<T> allowed, List<String> selected,
                                      java.util.function.Function<T, String> id) {
        if (selected == null) return allowed;
        var ids = allowed.stream().map(id).toList();
        if (selected.stream().anyMatch(s -> s == null || !ids.contains(s)))
            throw new IllegalArgumentException("Capability selection exceeds the published agent permissions");
        return allowed.stream().filter(v -> selected.contains(id.apply(v))).toList();
    }
    @Override public String toString() { return "WorkflowAgentConfig[" + agentId + "]"; }
}
