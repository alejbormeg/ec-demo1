package io.mateu.ecdemo1.iacp.application.usecases.llm.update;

import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.ModelName;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Pricing;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.SamplingOptions;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateLlmUseCase {

    final LlmRepository repository;

    @Transactional
    public void handle(UpdateLlmCommand command) {
        var llm = repository.findById(new LlmId(command.id()))
                .orElseThrow(() -> new IllegalArgumentException("No LLM with id '" + command.id() + "'"));
        llm.update(new Name(command.name()), command.provider(), new ModelName(command.model()),
                command.baseUrl() == null || command.baseUrl().isBlank() ? null : command.baseUrl(),
                new SamplingOptions(command.temperature(), command.maxTokens()),
                new Pricing(command.priceCurrency(), command.inputPricePerMillion(),
                        command.outputPricePerMillion()),
                new Enabled(command.enabled()));
        repository.save(llm);
    }
}
