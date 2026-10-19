package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.query.RagQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.dto.RagDto;
import io.mateu.ecdemo1.iacp.application.out.query.dto.RagRow;
import io.mateu.ecdemo1.iacp.application.out.query.dto.StatusBadge;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind;
import io.mateu.ecdemo1.uicommons.paging.DbPaging;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RagDBQueryService implements RagQueryService {

    final RagEntityRepository entities;
    final ProtectedRagConnections connections;

    @Override
    public ListingData<RagRow> findAll(String searchText, Object filters, Pageable pageable) {
        var springPageable = DbPaging.pageable(pageable);
        var page = searchText == null || searchText.isBlank()
                ? entities.findAll(springPageable)
                : entities.findByNameContainingIgnoreCase(searchText, springPageable);
        return DbPaging.listing(searchText, page, RagDBQueryService::toRow);
    }

    @Override
    public String getLabel(String id) {
        return entities.findById(id).map(RagEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<RagDto> getById(String id) {
        return entities.findById(id).map(this::toDto);
    }

    @Override
    public List<RagDto> all() {
        return entities.findAll().stream().map(this::toDto).toList();
    }

    @Override
    public long count() {
        return entities.count();
    }

    @Override
    public long countEnabled() {
        return entities.countByEnabledTrue();
    }

    RagDto toDto(RagEntity e) {
        return new RagDto(e.getId(), e.getName(), RagKind.valueOf(e.getKind()),
                connections.reveal(e.getConnectionUrl()), e.getCollectionName(), e.getEmbeddingLlmId(),
                e.getTopK(), e.getDescription(), e.isEnabled(), e.getCreated());
    }

    static RagRow toRow(RagEntity e) {
        return new RagRow(e.getId(), e.getName(), e.getKind(), e.getCollectionName(),
                StatusBadge.enabled(e.isEnabled()));
    }
}
