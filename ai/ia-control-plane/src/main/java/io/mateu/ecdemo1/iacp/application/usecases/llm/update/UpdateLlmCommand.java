package io.mateu.ecdemo1.iacp.application.usecases.llm.update;

import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;

/**
 * Note what is not here: the API key. An LLM's credential is replaced through its own use case,
 * so an ordinary save cannot blank a working key just because the form rendered it empty — which
 * is exactly what a write-only field does when it is wired through the update path.
 */
public record UpdateLlmCommand(String id, String name, LlmProvider provider, String model,
                               String baseUrl, Double temperature, Integer maxTokens,
                               String priceCurrency, Double inputPricePerMillion,
                               Double outputPricePerMillion, boolean enabled) {
}
