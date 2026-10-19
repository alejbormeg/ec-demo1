package io.mateu.ecdemo1.iacp.application.usecases.agent.create;

import java.util.List;
import java.util.Map;

public record CreateAgentCommand(String id, String name, String systemPrompt, String llmId,
                                 List<String> mcpIds, List<String> ragIds, List<String> peerAgentIds,
                                 String description, Map<String, Object> outputSchema) {
    public CreateAgentCommand(String id, String name, String systemPrompt, String llmId,
                              List<String> mcpIds, List<String> ragIds, List<String> peerAgentIds,
                              String description) {
        this(id, name, systemPrompt, llmId, mcpIds, ragIds, peerAgentIds, description, null);
    }
}
