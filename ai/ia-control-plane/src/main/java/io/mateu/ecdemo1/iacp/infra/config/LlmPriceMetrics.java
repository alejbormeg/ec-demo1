package io.mateu.ecdemo1.iacp.infra.config;

import io.mateu.ecdemo1.iacp.application.out.query.LlmQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.dto.LlmDto;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalogue's prices, for Grafana to turn token counts into money.
 *
 * <p>{@code ia_llm_price_per_mtoken{llm, model, provider, currency, type}} — one series per priced
 * LLM and token type (input / output). The agents' tokens ({@code ia_tokens_total}) carry the
 * catalogue id as {@code llm}; a workflow's LLM_CALL steps carry the provider's model id as
 * {@code model}. Both labels are here, so a dashboard joins on whichever its counter has.
 *
 * <p>A MultiGauge, rebuilt every minute from the catalogue: the set of rows changes as models are
 * added, priced or deleted, which a fixed gauge per row cannot follow. A model without a price is
 * absent, not zero — the cost panels then leave it out instead of calling it free.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class LlmPriceMetrics {

    final MeterRegistry registry;
    final LlmQueryService llms;

    MultiGauge prices;

    @PostConstruct
    void register() {
        prices = MultiGauge.builder("ia_llm_price_per_mtoken")
                .description("What a million tokens of a catalogued LLM cost, by token type")
                .register(registry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${cp.metrics.prices-refresh-ms:60000}", initialDelay = 60000)
    void refresh() {
        try {
            prices.register(rows(llms.all()), true);
        } catch (Exception e) {
            // A scrape-side gauge must never take the service down; the next refresh retries.
            log.warn("Could not refresh the LLM price gauges: {}", e.toString());
        }
    }

    static List<MultiGauge.Row<?>> rows(List<LlmDto> catalogue) {
        var rows = new ArrayList<MultiGauge.Row<?>>();
        for (var llm : catalogue) {
            var currency = llm.priceCurrency() == null || llm.priceCurrency().isBlank()
                    ? "EUR" : llm.priceCurrency();
            var tags = Tags.of("llm", llm.id(), "model", llm.model(),
                    "provider", llm.provider().name(), "currency", currency);
            if (llm.inputPricePerMillion() != null) {
                rows.add(MultiGauge.Row.of(tags.and("type", "input"), llm.inputPricePerMillion()));
            }
            if (llm.outputPricePerMillion() != null) {
                rows.add(MultiGauge.Row.of(tags.and("type", "output"), llm.outputPricePerMillion()));
            }
        }
        return rows;
    }
}
