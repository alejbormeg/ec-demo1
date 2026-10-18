package io.mateu.ecdemo1.pmsintegration.demo;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import io.mateu.ecdemo1.pmsintegration.config.OperaContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Task new-opera-context (process reset-demo): a context of its own for the demo's next run,
 * {@code ECDEMO1-<MMddHHmm>} (UTC), taken at once — no restart — and kept in the run's ConfigMap for
 * the next startup and the scripts. What earlier runs wrote stays in Opera under their own contexts —
 * cancelled by cancel-earlier-runs ({@link EarlierRuns}), not deleted.
 *
 * <p>Idempotent: the process that already set a context gets that same one back, from memory or from
 * the ConfigMap, never a second. Written to the ConfigMap first and only then taken: a write that
 * fails changes nothing, and the engine retries.
 */
@Slf4j
public class NewOperaContext {

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMddHHmm").withZone(ZoneOffset.UTC);

    public record Output(String operaContext, String operaCustomReference) {
    }

    private final OperaContext context;
    private final RunConfig runConfig;
    private final Clock clock;

    public NewOperaContext(OperaContext context, RunConfig runConfig, Clock clock) {
        this.context = context;
        this.runConfig = runConfig;
        this.clock = clock;
    }

    public synchronized Output renew(String processKey) {
        if (processKey == null || processKey.isBlank()) {
            throw new IllegalArgumentException("new-opera-context needs the process' processKey");
        }
        var current = context.value();
        if (processKey.equals(current.processKey())) {
            return new Output(current.externalSystemCode(), current.customReference());
        }
        var stored = runConfig.read().filter(s -> processKey.equals(s.processKey()));
        if (stored.isPresent()) {
            take(stored.get());
            return new Output(stored.get().externalSystemCode(), stored.get().customReference());
        }
        var fresh = OperaContext.LEGACY + "-" + STAMP.format(clock.instant());
        var next = new RunConfig.Stored(fresh, OperaContext.customReferenceFor(fresh), processKey);
        runConfig.write(next);
        take(next);
        return new Output(next.externalSystemCode(), next.customReference());
    }

    void take(RunConfig.Stored stored) {
        var was = context.value();
        context.set(new OperaContext.Value(stored.externalSystemCode(), stored.customReference(), stored.processKey()));
        log.info("Opera context {} (custom reference {}) for {}, was {}", stored.externalSystemCode(),
                stored.customReference(), stored.processKey(), was.externalSystemCode());
    }
}
