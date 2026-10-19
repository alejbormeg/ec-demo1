package io.mateu.ecdemo1.iacp.application.out.gitops.manifest;

import java.util.List;
import java.util.Map;

/**
 * One agent entry as the repo declares it. {@code llm}, {@code mcp} and {@code rag} are the ids of
 * entries in the other three catalogues, and {@code peers} the ids of the other agents this one may
 * call over A2A; they are not validated here, for the same reason the
 * domain does not validate them — an agent is resolved at read time, dropping what is no longer
 * usable, so a reference to something not yet synced is a warning later, not a failure now.
 */
public record AgentManifest(String id, String name, String systemPrompt, String llm,
                            List<String> mcp, List<String> rag, List<String> peers,
                            String description, Boolean enabled, Map<String, Object> outputSchema) {
    public AgentManifest(String id, String name, String systemPrompt, String llm,
                         List<String> mcp, List<String> rag, List<String> peers,
                         String description, Boolean enabled) {
        this(id, name, systemPrompt, llm, mcp, rag, peers, description, enabled, null);
    }
}
