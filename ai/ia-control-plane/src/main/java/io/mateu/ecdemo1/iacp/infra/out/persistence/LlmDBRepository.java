package io.mateu.ecdemo1.iacp.infra.out.persistence;

import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.Llm;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Credential;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Pricing;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.ModelName;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.SamplingOptions;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LlmDBRepository implements LlmRepository {

    final LlmEntityRepository entities;

    @Override
    public Llm save(Llm llm) {
        var e = entities.findById(llm.getId().value()).orElseGet(LlmEntity::new);
        e.setId(llm.getId().value());
        e.setName(llm.getName().value());
        e.setProvider(llm.getProvider().name());
        e.setModel(llm.getModel().value());
        e.setBaseUrl(llm.getBaseUrl());
        e.setTemperature(llm.getSampling().temperature());
        e.setMaxTokens(llm.getSampling().maxTokens());
        e.setPriceCurrency(llm.getPricing().isSet() ? llm.getPricing().currency() : null);
        e.setInputPricePerMillion(llm.getPricing().inputPerMillion());
        e.setOutputPricePerMillion(llm.getPricing().outputPerMillion());
        e.setCredential(llm.getCredential().cipherText());
        e.setEnabled(llm.getEnabled().value());
        e.setCreated(llm.getCreated().value());
        entities.save(e);
        return llm;
    }

    @Override
    public Optional<Llm> findById(LlmId id) {
        return entities.findById(id.value()).map(LlmDBRepository::toDomain);
    }

    @Override
    public List<Llm> findAll() {
        return entities.findAll().stream().map(LlmDBRepository::toDomain).toList();
    }

    @Override
    public void deleteAllById(List<LlmId> ids) {
        entities.deleteAllById(ids.stream().map(LlmId::value).toList());
    }

    @Override
    public boolean existsById(LlmId id) {
        return entities.existsById(id.value());
    }

    static Llm toDomain(LlmEntity e) {
        return new Llm(
                new LlmId(e.getId()),
                new Name(e.getName()),
                LlmProvider.valueOf(e.getProvider()),
                new ModelName(e.getModel()),
                e.getBaseUrl(),
                new SamplingOptions(e.getTemperature(), e.getMaxTokens()),
                new Pricing(e.getPriceCurrency(), e.getInputPricePerMillion(), e.getOutputPricePerMillion()),
                new Credential(e.getCredential()),
                new Enabled(e.isEnabled()),
                new Time(e.getCreated()));
    }
}
