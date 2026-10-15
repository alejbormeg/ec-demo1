package io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo;

/**
 * What a million tokens of this model cost, in and out.
 *
 * <p>The catalogue is where a model's price belongs: it is the one place that knows which
 * provider model an id like {@code alejandro} stands for, and the token counts the agents report
 * are labelled with that id. Grafana multiplies them by the price the control plane exports, so a
 * price changed here is the cost the dashboard shows from the next scrape on.
 *
 * <p>Both prices are optional — a local model costs nothing worth counting, and a gateway's price
 * may simply not be known. An entry without them is left out of the cost panels rather than shown
 * as free. The currency is whatever the prices are written in; it defaults to EUR, and the panels
 * add up each currency on its own instead of pretending to convert.
 */
public record Pricing(String currency, Double inputPerMillion, Double outputPerMillion) {

    public static final String DEFAULT_CURRENCY = "EUR";

    public Pricing {
        if (inputPerMillion != null && inputPerMillion < 0) {
            throw new IllegalArgumentException("The input price cannot be negative");
        }
        if (outputPerMillion != null && outputPerMillion < 0) {
            throw new IllegalArgumentException("The output price cannot be negative");
        }
        currency = currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency.trim().toUpperCase();
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("The currency is a three-letter code, e.g. EUR or USD");
        }
    }

    public static Pricing none() {
        return new Pricing(null, null, null);
    }

    public boolean isSet() {
        return inputPerMillion != null || outputPerMillion != null;
    }
}
