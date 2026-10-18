package io.mateu.ecdemo1.pmsintegration.config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The context this run of the demo writes to Opera under: the CRS locator's external-reference
 * context ({@code OPERA_EXTERNAL_SYSTEM}) and the reservations' «Custom Reference»
 * ({@code OPERA_CUSTOM_REFERENCE}). Taken from {@link OhipProperties} at startup and swapped at once,
 * without a restart, when the demo goes back to zero (task new-opera-context, process reset-demo).
 * An earlier run's reservations are cancelled, not deleted ({@code EarlierRuns}): a new locator that
 * repeats an old one must not find an old run's reservation.
 */
public class OperaContext {

    /** The context everything was written under before there was one per run. */
    public static final String LEGACY = "ECDEMO1";

    public record Value(String externalSystemCode, String customReference, String processKey) {
    }

    private final AtomicReference<Value> current;

    public OperaContext(String externalSystemCode, String customReference) {
        current = new AtomicReference<>(new Value(externalSystemCode, customReference == null ? "" : customReference, null));
    }

    public static OperaContext of(OhipProperties properties) {
        return new OperaContext(properties.externalSystemCode(), properties.customReference());
    }

    /** The custom reference of a context: its own name, but the legacy ECDEMO1's is EC-DEMO1 (deploy/demo/common.sh). */
    public static String customReferenceFor(String context) {
        return LEGACY.equals(context) ? "EC-DEMO1" : context;
    }

    public String externalSystemCode() {
        return current.get().externalSystemCode();
    }

    public String customReference() {
        return current.get().customReference();
    }

    public Value value() {
        return current.get();
    }

    public void set(Value value) {
        current.set(value);
    }
}
