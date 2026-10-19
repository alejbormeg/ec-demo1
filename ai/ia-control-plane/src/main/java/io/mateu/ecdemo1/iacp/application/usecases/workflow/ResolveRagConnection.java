package io.mateu.ecdemo1.iacp.application.usecases.workflow;

import io.mateu.ecdemo1.iacp.application.out.gitops.CredentialResolver;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.RagManifest;

/** Resolve a secret-backed connection without adding credentials to a Git manifest or domain type. */
public final class ResolveRagConnection {
    private ResolveRagConnection() {}
    public static String resolve(RagManifest manifest, CredentialResolver environment) {
        boolean fromEnvironment = manifest.connectionEnv() != null && !manifest.connectionEnv().isBlank();
        boolean inline = manifest.connectionUrl() != null && !manifest.connectionUrl().isBlank();
        if (fromEnvironment == inline)
            throw new IllegalArgumentException("RAG requires exactly one of connectionUrl or connectionEnv");
        String value = fromEnvironment ? environment.resolve(manifest.connectionEnv()) : manifest.connectionUrl();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("RAG connection environment is missing or empty");
        return value;
    }
}
