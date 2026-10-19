package io.mateu.ecdemo1.iacp.application.out.gitops.manifest;

import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind;

/**
 * One RAG source entry as the repo declares it. The vector store type is {@code store} here, not
 * {@code kind}: {@code kind} is the file-level discriminator that says this is a RAG entry at all,
 * so the store had to take a name of its own to avoid two meanings for one word.
 */
public record RagManifest(String id, String name, RagKind store, String connectionUrl,
                          String collection, String embeddingLlmId, Integer topK,
                          String description, Boolean enabled, String connectionEnv) {
    public RagManifest(String id, String name, RagKind store, String connectionUrl,
                       String collection, String embeddingLlmId, Integer topK,
                       String description, Boolean enabled) {
        this(id, name, store, connectionUrl, collection, embeddingLlmId, topK, description, enabled, null);
    }
}
