package io.mateu.ecdemo1.iacp.application.usecases.workflow;

import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.RagManifest;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResolveRagConnectionTest {
    RagManifest source(String url, String env) {
        return new RagManifest("synthetic", "Synthetic", RagKind.PGVECTOR, url, "synthetic", "embedding", 3, "Synthetic", true, env);
    }
    @Test void resolvesProtectedEnvironmentAndPreservesLegacyInlineUrls() {
        var url = "jdbc:postgresql://synthetic/db?user=synthetic&password=synthetic-password";
        assertEquals(url, ResolveRagConnection.resolve(source(null, "SYNTHETIC_RAG_URL"), name -> url));
        assertEquals(url, ResolveRagConnection.resolve(source(url, null), name -> { throw new AssertionError("No environment lookup for legacy inline URL"); }));
    }
    @Test void missingEmptyAndAmbiguousConfigurationFailWithoutFallback() {
        assertThrows(IllegalArgumentException.class, () -> ResolveRagConnection.resolve(source(null, "UNKNOWN"), name -> null));
        assertThrows(IllegalArgumentException.class, () -> ResolveRagConnection.resolve(source(null, "EMPTY"), name -> " "));
        assertThrows(IllegalArgumentException.class, () -> ResolveRagConnection.resolve(source("jdbc:postgresql://synthetic/db", "ENV"), name -> "jdbc:postgresql://other/db"));
        assertThrows(IllegalArgumentException.class, () -> ResolveRagConnection.resolve(source(null, null), name -> null));
    }
}
