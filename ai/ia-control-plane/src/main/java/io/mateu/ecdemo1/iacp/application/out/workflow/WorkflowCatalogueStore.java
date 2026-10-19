package io.mateu.ecdemo1.iacp.application.out.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable, encrypted publications and immutable invocation snapshots. */
public interface WorkflowCatalogueStore {
    record FrozenRag(String id, String name, String kind, String connectionUrl, String collection,
                     String embeddingLlmId, String embeddingModel, String embeddingBaseUrl,
                     String embeddingApiKey, int topK, String description) {
        @Override public String toString() { return "FrozenRag[" + id + "]"; }
    }
    record Publication(String revision, Map<String, WorkflowAgentConfig> agents,
                       Map<String, FrozenRag> retrieval) {
        @Override public String toString() { return "WorkflowPublication[" + revision + "]"; }
    }
    record Snapshot(String snapshotId, String digest, String catalogueRevision, String rootAgentId,
                    Map<String, WorkflowAgentConfig> agents, Map<String, FrozenRag> retrieval,
                    String requestDigest, String selectionDigest, String technicalActor) {
        @Override public String toString() { return "WorkflowSnapshot[" + snapshotId + "]"; }
    }
    Optional<Publication> publication();
    void publish(Publication publication);
    Optional<Snapshot> find(String invocationId);
    /** Insert-if-absent, returning the stored winner even with concurrent resolvers. */
    Snapshot freeze(Snapshot snapshot);
    String digest(Object value);
    /** Record a stable agent invocation/round exactly once, atomically with token usage. */
    boolean recordUsage(String eventId, String eventDigest, String agentId, String llmId, String model,
                        int inputTokens, int outputTokens, String actor, String invocationId);
}
