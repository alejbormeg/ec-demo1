package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.query.AgentQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.dto.AgentDto;
import io.mateu.ecdemo1.iacp.application.out.query.dto.AgentRow;
import io.mateu.ecdemo1.iacp.application.out.query.dto.StatusBadge;
import io.mateu.ecdemo1.uicommons.paging.DbPaging;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AgentDBQueryService implements AgentQueryService {

    final AgentEntityRepository entities;

    @Override
    public ListingData<AgentRow> findAll(String searchText, Object filters, Pageable pageable) {
        var springPageable = DbPaging.pageable(pageable);
        var page = searchText == null || searchText.isBlank()
                ? entities.findAll(springPageable)
                : entities.findByNameContainingIgnoreCase(searchText, springPageable);
        return DbPaging.listing(searchText, page, AgentDBQueryService::toRow);
    }

    @Override
    public String getLabel(String id) {
        return entities.findById(id).map(AgentEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<AgentDto> getById(String id) {
        return entities.findById(id).map(AgentDBQueryService::toDto);
    }

    @Override
    public List<AgentDto> all() {
        return entities.findAll().stream().map(AgentDBQueryService::toDto).toList();
    }

    @Override
    public long count() {
        return entities.count();
    }

    @Override
    public long countEnabled() {
        return entities.countByEnabledTrue();
    }

    static AgentDto toDto(AgentEntity e) {
        return new AgentDto(e.getId(), e.getName(), e.getSystemPrompt(), e.getLlmId(),
                IdList.split(e.getMcpIds()), IdList.split(e.getRagIds()),
                IdList.split(e.getPeerAgentIds()), e.getDescription(), e.isEnabled(), e.getCreated(), JsonDocuments.readMap(e.getOutputSchema()));
    }

    static AgentRow toRow(AgentEntity e) {
        return new AgentRow(e.getId(), e.getName(), e.getLlmId(),
                IdList.split(e.getMcpIds()).size(), IdList.split(e.getRagIds()).size(),
                IdList.split(e.getPeerAgentIds()).size(),
                StatusBadge.enabled(e.isEnabled()));
    }
}
