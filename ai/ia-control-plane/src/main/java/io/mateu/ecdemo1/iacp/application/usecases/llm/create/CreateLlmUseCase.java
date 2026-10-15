package io.mateu.ecdemo1.iacp.application.usecases.llm.create;

import io.mateu.ecdemo1.iacp.application.out.crypto.SecretCipher;
import io.mateu.ecdemo1.iacp.application.out.repository.LlmRepository;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.Llm;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Credential;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.ModelName;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Pricing;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.SamplingOptions;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateLlmUseCase {

    final LlmRepository repository;
    final SecretCipher cipher;

    @Transactional
    public String handle(CreateLlmCommand command) {
        var id = new LlmId(command.id());
        if (repository.existsById(id)) {
            throw new IllegalArgumentException("An LLM with id '" + command.id() + "' already exists");
        }
        var llm = Llm.of(id, new Name(command.name()), command.provider(),
                new ModelName(command.model()), blankToNull(command.baseUrl()),
                new SamplingOptions(command.temperature(), command.maxTokens()),
                new Pricing(command.priceCurrency(), command.inputPricePerMillion(),
                        command.outputPricePerMillion()));
        // The key is encrypted here and never held in the clear beyond this statement — the
        // aggregate only ever receives ciphertext. See Credential.
        if (command.apiKey() != null && !command.apiKey().isBlank()) {
            llm.replaceCredential(new Credential(cipher.encrypt(command.apiKey())));
        }
        repository.save(llm);
        return id.value();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
