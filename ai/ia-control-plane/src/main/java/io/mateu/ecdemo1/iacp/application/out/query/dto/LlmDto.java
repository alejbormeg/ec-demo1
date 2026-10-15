package io.mateu.ecdemo1.iacp.application.out.query.dto;

import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;

import java.time.LocalDateTime;

/**
 * An LLM as the UI and the configuration endpoint see it.
 *
 * <p>{@code credentialSet} rather than the credential: not even the ciphertext leaves the read
 * side. The one place that needs the plaintext decrypts it from the aggregate — see
 * {@code AgentConfigAssembler} — and that path is not reachable from the console.
 */
public record LlmDto(
        String id,
        String name,
        LlmProvider provider,
        String model,
        String baseUrl,
        Double temperature,
        Integer maxTokens,
        String priceCurrency,
        Double inputPricePerMillion,
        Double outputPricePerMillion,
        boolean credentialSet,
        boolean enabled,
        LocalDateTime created) {
}
