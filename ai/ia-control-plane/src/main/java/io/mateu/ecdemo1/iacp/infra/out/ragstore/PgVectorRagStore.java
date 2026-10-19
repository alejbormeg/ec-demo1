package io.mateu.ecdemo1.iacp.infra.out.ragstore;

import io.mateu.ecdemo1.iacp.application.out.rag.RagStore;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.Rag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieval against pgvector, and nothing else — the other two catalogued kinds are refused with a
 * sentence saying so.
 *
 * <p><strong>Everything is per source and cached.</strong> A RAG source names its own connection
 * URL and its own embedding model, so two sources can live in different databases and be embedded
 * by different models. That means a DataSource, an embedding client and a store per source rather
 * than one of each for the service, keyed by everything that would make them different. The
 * embedding key is part of the key: rotating it has to build a new client, exactly as it does for
 * a chat model.
 *
 * <p><strong>The table is created on first use.</strong> {@code initializeSchema(true)} makes
 * Spring AI create the vector table, its index and the {@code vector} extension if they are not
 * there. That is the right default for a catalogue where a source is declared before it holds
 * anything — the alternative is that every new source needs a DBA before it can be used once.
 * It is also why the database user this connects with needs rights to create an extension.
 *
 * <p><strong>Dimensions are the embedding model's, not a setting.</strong> They are left for
 * Spring AI to derive by asking the model, because a mismatch between the column and the vectors
 * is not a slow query, it is an insert that fails — and hardcoding a number here would only make
 * that failure appear on the second embedding model rather than the first.
 */
@Component
public class PgVectorRagStore implements RagStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorRagStore.class);

    private final Map<String, VectorStore> stores = new ConcurrentHashMap<>();
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    @Override
    public List<Chunk> search(Rag rag, EmbeddingSpec embedding, String query, int topK) {
        var store = storeFor(rag, embedding);
        try {
            var documents = store.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK > 0 ? topK : rag.getTopK())
                    .build());
            if (documents == null) {
                return List.of();
            }
            return documents.stream()
                    .map(d -> new Chunk(d.getText(), score(d), String.valueOf(
                            d.getMetadata().getOrDefault("source", rag.getName().value()))))
                    .toList();
        } catch (Exception e) {
            throw new UnsupportedStoreException("Search against '" + rag.getName()
                    + "' failed: " + rootMessage(e), e);
        }
    }

    @Override
    public int ingest(Rag rag, EmbeddingSpec embedding, List<String> texts) {
        var store = storeFor(rag, embedding);
        var documents = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", rag.getName().value());
                    return new Document(t, metadata);
                })
                .toList();
        if (documents.isEmpty()) {
            return 0;
        }
        // Split before storing: a whole document embedded as one vector retrieves as all-or-
        // nothing and drowns the useful sentence in the rest of the page.
        var chunks = splitter.apply(documents);
        try {
            store.add(chunks);
        } catch (Exception e) {
            throw new UnsupportedStoreException("Writing to '" + rag.getName()
                    + "' failed: " + rootMessage(e), e);
        }
        log.info("Ingested {} chunk(s) from {} text(s) into RAG source {}",
                chunks.size(), documents.size(), rag.getId());
        return chunks.size();
    }

    private VectorStore storeFor(Rag rag, EmbeddingSpec embedding) {
        if (rag.getKind() != io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind.PGVECTOR) {
            throw new UnsupportedStoreException("RAG source '" + rag.getName() + "' is a "
                    + rag.getKind() + " store. Only PGVECTOR is implemented; the others can be "
                    + "catalogued but not queried.");
        }
        if (embedding.apiKey() == null || embedding.apiKey().isBlank()) {
            throw new UnsupportedStoreException("The embedding model for '" + rag.getName()
                    + "' has no credential, so a query cannot be embedded.");
        }
        var key = rag.getId().value() + "|" + rag.getConnectionUrl() + "|" + rag.getCollection()
                + "|" + embedding.model() + "|" + embedding.baseUrl() + "|" + embedding.apiKey();
        return stores.computeIfAbsent(key, k -> {
            log.info("Opening vector store for RAG source {} ({}, table {})",
                    rag.getId(), rag.getKind(), rag.getCollection());
            try {
                var dataSource = new DriverManagerDataSource(rag.getConnectionUrl());
                dataSource.setDriverClassName("org.postgresql.Driver");
                var store = PgVectorStore
                        .builder(new JdbcTemplate(dataSource), embeddingModel(embedding))
                        .vectorTableName(rag.getCollection())
                        .initializeSchema(true)
                        .build();
                // The builder does not touch the database; this is what creates the extension,
                // the table and the index. Doing it here rather than lazily means a broken
                // connection URL fails now, with this message, rather than inside a prompt.
                store.afterPropertiesSet();
                return store;
            } catch (Exception e) {
                throw new UnsupportedStoreException("Could not open RAG source '" + rag.getId()
                        + "'. Check the protected store connection and database availability.");
            }
        });
    }

    private EmbeddingModel embeddingModel(EmbeddingSpec spec) {
        return new OpenAiCompatibleEmbeddingModel(spec);
    }

    private static double score(Document d) {
        var score = d.getScore();
        return score == null ? 0.0 : score;
    }

    /**
     * The message a person can act on. A JDBC failure arrives wrapped three deep, and the
     * outermost message is almost always the least informative of the three.
     */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
