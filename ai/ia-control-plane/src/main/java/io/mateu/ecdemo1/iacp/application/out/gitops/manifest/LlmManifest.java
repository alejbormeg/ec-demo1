package io.mateu.ecdemo1.iacp.application.out.gitops.manifest;

import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;

/**
 * One LLM entry as the repo declares it. The field that is conspicuously absent is the API key:
 * a repo, public or not, is the wrong place for a credential. Instead {@code credentialEnv} names
 * an environment variable, and the control plane resolves it from its own Secret at sync time — so
 * the repo says <em>which</em> secret, and only the deployment holds the secret itself.
 */
public record LlmManifest(String id, String name, LlmProvider provider, String model,
                          String baseUrl, Double temperature, Integer maxTokens,
                          PricingManifest pricing, String credentialEnv, Boolean enabled) {

    /** What a million tokens cost, in and out — see {@code Pricing}. Every field optional. */
    public record PricingManifest(String currency, Double input, Double output) {
    }

    public String priceCurrency() {
        return pricing == null ? null : pricing.currency();
    }

    public Double inputPricePerMillion() {
        return pricing == null ? null : pricing.input();
    }

    public Double outputPricePerMillion() {
        return pricing == null ? null : pricing.output();
    }
}
