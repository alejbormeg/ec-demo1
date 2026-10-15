package io.mateu.ecdemo1.iacp.application.usecases.llm.create;

import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;

public record CreateLlmCommand(String id, String name, LlmProvider provider, String model,
                               String baseUrl, Double temperature, Integer maxTokens,
                               String priceCurrency, Double inputPricePerMillion,
                               Double outputPricePerMillion, String apiKey) {
}
