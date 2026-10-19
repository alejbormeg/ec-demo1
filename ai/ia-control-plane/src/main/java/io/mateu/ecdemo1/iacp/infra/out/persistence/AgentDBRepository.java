package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.repository.AgentRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.Agent;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.AgentId;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.SystemPrompt;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagId;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AgentDBRepository implements AgentRepository {

    final AgentEntityRepository entities;

    @Override
    public Agent save(Agent agent) {
        var e = entities.findById(agent.getId().value()).orElseGet(AgentEntity::new);
        e.setId(agent.getId().value());
        e.setName(agent.getName().value());
        e.setSystemPrompt(agent.getSystemPrompt().value());
        e.setLlmId(agent.getLlmId().value());
        e.setMcpIds(IdList.join(agent.getMcpIds().stream().map(McpId::value).toList()));
        e.setRagIds(IdList.join(agent.getRagIds().stream().map(RagId::value).toList()));
        e.setPeerAgentIds(IdList.join(agent.getPeerAgentIds().stream().map(AgentId::value).toList()));
        e.setDescription(agent.getDescription());
        e.setOutputSchema(JsonDocuments.write(agent.getOutputSchema()));
        e.setEnabled(agent.getEnabled().value());
        e.setCreated(agent.getCreated().value());
        entities.save(e);
        return agent;
    }

    @Override
    public Optional<Agent> findById(AgentId id) {
        return entities.findById(id.value()).map(AgentDBRepository::toDomain);
    }

    @Override
    public List<Agent> findAll() {
        return entities.findAll().stream().map(AgentDBRepository::toDomain).toList();
    }

    @Override
    public void deleteAllById(List<AgentId> ids) {
        entities.deleteAllById(ids.stream().map(AgentId::value).toList());
    }

    @Override
    public boolean existsById(AgentId id) {
        return entities.existsById(id.value());
    }

    static Agent toDomain(AgentEntity e) {
        return new Agent(
                new AgentId(e.getId()),
                new Name(e.getName()),
                new SystemPrompt(e.getSystemPrompt()),
                new LlmId(e.getLlmId()),
                IdList.split(e.getMcpIds()).stream().map(McpId::new).toList(),
                IdList.split(e.getRagIds()).stream().map(RagId::new).toList(),
                IdList.split(e.getPeerAgentIds()).stream().map(AgentId::new).toList(),
                e.getDescription(),
                new Enabled(e.isEnabled()),
                new Time(e.getCreated()), JsonDocuments.readMap(e.getOutputSchema()));
    }
}
