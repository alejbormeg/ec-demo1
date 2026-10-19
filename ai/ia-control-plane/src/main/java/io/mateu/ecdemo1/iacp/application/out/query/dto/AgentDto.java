package io.mateu.ecdemo1.iacp.application.out.query.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AgentDto(
        String id,
        String name,
        String systemPrompt,
        String llmId,
        List<String> mcpIds,
        List<String> ragIds,
        List<String> peerAgentIds,
        String description,
        boolean enabled,
        LocalDateTime created, Map<String, Object> outputSchema) {
    public AgentDto(String id, String name, String systemPrompt, String llmId,
                    List<String> mcpIds, List<String> ragIds, List<String> peerAgentIds,
                    String description, boolean enabled, LocalDateTime created) {
        this(id, name, systemPrompt, llmId, mcpIds, ragIds, peerAgentIds, description, enabled, created, null);
    }
}
