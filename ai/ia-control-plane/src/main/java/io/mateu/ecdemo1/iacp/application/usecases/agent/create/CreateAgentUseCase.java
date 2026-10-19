package io.mateu.ecdemo1.iacp.application.usecases.agent.create;

import io.mateu.ecdemo1.iacp.application.out.repository.AgentRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.Agent;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.AgentId;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.SystemPrompt;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagId;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CreateAgentUseCase {

    final AgentRepository repository;
    final LlmRepository llmRepository;

    @Transactional
    public String handle(CreateAgentCommand command) {
        var id = new AgentId(command.id());
        if (repository.existsById(id)) {
            throw new IllegalArgumentException("An agent with id '" + command.id() + "' already exists");
        }
        var llmId = new LlmId(command.llmId());
        // The one reference worth refusing on: an agent with no model cannot be served at all,
        // where a missing MCP or RAG only makes it less capable.
        if (!llmRepository.existsById(llmId)) {
            throw new IllegalArgumentException("No LLM with id '" + command.llmId() + "'");
        }
        var agent = Agent.of(id, new Name(command.name()),
                new SystemPrompt(command.systemPrompt()), llmId,
                ids(command.mcpIds(), McpId::new), ids(command.ragIds(), RagId::new),
                ids(command.peerAgentIds(), AgentId::new), command.description());
        agent.defineOutputSchema(command.outputSchema());
        repository.save(agent);
        return id.value();
    }

    public static <T> List<T> ids(List<String> raw, java.util.function.Function<String, T> factory) {
        return raw == null ? List.of() : raw.stream().filter(s -> s != null && !s.isBlank())
                .map(factory).toList();
    }
}
