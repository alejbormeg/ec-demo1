package io.mateu.ecdemo1.iacp.application.usecases.agent.update;

import io.mateu.ecdemo1.iacp.application.out.repository.AgentRepository;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentUseCase;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.AgentId;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.SystemPrompt;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagId;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateAgentUseCase {

    final AgentRepository repository;
    final LlmRepository llmRepository;

    @Transactional
    public void handle(UpdateAgentCommand command) {
        var agent = repository.findById(new AgentId(command.id()))
                .orElseThrow(() -> new IllegalArgumentException("No agent with id '" + command.id() + "'"));
        var llmId = new LlmId(command.llmId());
        if (!llmRepository.existsById(llmId)) {
            throw new IllegalArgumentException("No LLM with id '" + command.llmId() + "'");
        }
        agent.update(new Name(command.name()), new SystemPrompt(command.systemPrompt()), llmId,
                CreateAgentUseCase.ids(command.mcpIds(), McpId::new),
                CreateAgentUseCase.ids(command.ragIds(), RagId::new),
                CreateAgentUseCase.ids(command.peerAgentIds(), AgentId::new),
                command.description(), new Enabled(command.enabled()));
        agent.defineOutputSchema(command.outputSchema());
        repository.save(agent);
    }
}
