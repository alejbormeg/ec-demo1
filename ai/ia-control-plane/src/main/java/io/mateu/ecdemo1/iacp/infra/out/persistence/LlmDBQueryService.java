package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.query.LlmQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.dto.LlmDto;
import io.mateu.ecdemo1.iacp.application.out.query.dto.LlmRow;
import io.mateu.ecdemo1.iacp.application.out.query.dto.StatusBadge;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmUsability;
import io.mateu.ecdemo1.uicommons.paging.DbPaging;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LlmDBQueryService implements LlmQueryService {

    final LlmEntityRepository entities;

    @Override
    public ListingData<LlmRow> findAll(String searchText, Object filters, Pageable pageable) {
        var springPageable = DbPaging.pageable(pageable);
        var page = searchText == null || searchText.isBlank()
                ? entities.findAll(springPageable)
                : entities.findByNameContainingIgnoreCase(searchText, springPageable);
        return DbPaging.listing(searchText, page, LlmDBQueryService::toRow);
    }

    @Override
    public String getLabel(String id) {
        return entities.findById(id).map(LlmEntity::getName).orElse("Unknown");
    }

    @Override
    public Optional<LlmDto> getById(String id) {
        return entities.findById(id).map(LlmDBQueryService::toDto);
    }

    @Override
    public List<LlmDto> all() {
        return entities.findAll().stream().map(LlmDBQueryService::toDto).toList();
    }

    @Override
    public long count() {
        return entities.count();
    }

    @Override
    public long countEnabled() {
        return entities.countByEnabledTrue();
    }

    static LlmDto toDto(LlmEntity e) {
        return new LlmDto(e.getId(), e.getName(), LlmProvider.valueOf(e.getProvider()),
                e.getModel(), e.getBaseUrl(), e.getTemperature(), e.getMaxTokens(),
                e.getPriceCurrency(), e.getInputPricePerMillion(), e.getOutputPricePerMillion(),
                e.getCredential() != null && !e.getCredential().isBlank(),
                e.isEnabled(), e.getCreated());
    }

    static LlmRow toRow(LlmEntity e) {
        var credentialSet = e.getCredential() != null && !e.getCredential().isBlank();
        // The domain's answer, not a second one computed here. This column used to say "usable"
        // about an entry the control plane refused to serve, because it read the credential and
        // never the provider — the console asserting the opposite of what the agent was told.
        var usability = LlmUsability.of(e.isEnabled(),
                LlmProvider.valueOf(e.getProvider()), credentialSet);
        return new LlmRow(e.getId(), e.getName(), e.getProvider(), e.getModel(),
                credentialSet ? "set" : "missing", StatusBadge.of(usability.label()));
    }
}
