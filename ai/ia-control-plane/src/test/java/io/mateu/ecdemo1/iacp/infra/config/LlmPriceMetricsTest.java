package io.mateu.ecdemo1.iacp.infra.config;

import io.mateu.ecdemo1.iacp.application.out.query.dto.LlmDto;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.Pricing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The catalogue's prices reach Prometheus, and an unpriced model is absent rather than free. */
class LlmPriceMetricsTest {

    static LlmDto llm(String id, String model, String currency, Double in, Double out) {
        return new LlmDto(id, id, LlmProvider.ANTHROPIC, model, null, 0.1, 4096, currency, in, out,
                true, true, LocalDateTime.now());
    }

    @Test
    void onePerPricedTokenTypeAndNoneForAnUnpricedModel() {
        var registry = new SimpleMeterRegistry();
        var gauge = MultiGauge.builder("ia_llm_price_per_mtoken").register(registry);
        gauge.register(LlmPriceMetrics.rows(List.of(
                llm("anthropic", "claude-sonnet-4-5", "USD", 3.0, 15.0),
                llm("local", "llama", null, null, null),
                llm("half", "gpt", null, 1.0, null))), true);

        var meters = registry.find("ia_llm_price_per_mtoken").gauges();
        assertThat(meters).hasSize(3);
        assertThat(registry.get("ia_llm_price_per_mtoken").tags("llm", "anthropic", "type", "output",
                "model", "claude-sonnet-4-5", "currency", "USD").gauge().value()).isEqualTo(15.0);
        // no currency written: EUR
        assertThat(registry.get("ia_llm_price_per_mtoken").tags("llm", "half", "type", "input")
                .gauge().getId().getTag("currency")).isEqualTo("EUR");
        assertThat(registry.find("ia_llm_price_per_mtoken").tags("llm", "local").gauges()).isEmpty();
    }

    @Test
    void pricesAreNeverNegativeAndCurrenciesAreCodes() {
        assertThatThrownBy(() -> new Pricing("EUR", -1.0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pricing("euros", 1.0, 2.0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Pricing(" usd ", 1.0, 2.0).currency()).isEqualTo("USD");
        assertThat(Pricing.none().isSet()).isFalse();
    }
}
