package io.mateu.ecdemo1.iacp.application.usecases.agent.update;

import java.util.List;
import java.util.Map;

public record UpdateAgentCommand(String id, String name, String systemPrompt, String llmId,
                                 List<String> mcpIds, List<String> ragIds, List<String> peerAgentIds,
                                 String description,
                                 boolean enabled, Map<String, Object> outputSchema) {
    public UpdateAgentCommand(String id, String name, String systemPrompt, String llmId,
                              List<String> mcpIds, List<String> ragIds, List<String> peerAgentIds,
                              String description, boolean enabled) {
        this(id, name, systemPrompt, llmId, mcpIds, ragIds, peerAgentIds, description, enabled, null);
    }
}
