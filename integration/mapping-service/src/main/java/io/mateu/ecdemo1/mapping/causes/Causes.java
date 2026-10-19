package io.mateu.ecdemo1.mapping.causes;

import io.mateu.ecdemo1.mapping.audit.Audited;
import io.mateu.ecdemo1.integration.model.mapping.Cause;
import io.mateu.ecdemo1.integration.model.mapping.CauseType;
import io.mateu.ecdemo1.integration.model.mapping.CodeType;
import io.mateu.ecdemo1.integration.model.notification.NotificationRequested;
import io.mateu.ecdemo1.integration.model.notification.NotificationType;
import io.mateu.ecdemo1.integration.model.process.Definitions;
import io.mateu.ecdemo1.integration.model.process.ProcessVariables;
import io.mateu.ecdemo1.mapping.config.MappingProperties;
import io.mateu.ecdemo1.mapping.outbox.Outbox;
import io.mateu.ecdemo1.mapping.store.CauseRecord;
import io.mateu.ecdemo1.mapping.store.CauseRecordRepository;
import io.mateu.ecdemo1.mapping.store.CauseStatus;
import io.mateu.ecdemo1.mapping.store.Waiter;
import io.mateu.ecdemo1.mapping.store.WaiterCause;
import io.mateu.ecdemo1.mapping.store.WaiterCauseRepository;
import io.mateu.ecdemo1.mapping.store.WaiterRepository;
import io.mateu.ecdemo1.mapping.store.WaiterStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.security.AuthorizationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * The causes processes wait on, and the processes waiting on each (F012, HLA «Un proceso bloqueado
 * espera, no falla»).
 *
 * <p>The engine gives no loops, so a process does not wait and then go back to preparing. It waits
 * once — on a message correlated by its own key — and when its last cause is resolved it is sent
 * that message, resumes, and starts a fresh instance of itself that reads the reservation again.
 * The message is not buffered by the engine, and it can arrive before the process reaches its wait;
 * so it is sent again, until the process answers by asking to be relaunched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Causes {

    /**
     * What a successor is started with. The reception's processes (registrar-checkin, -checkout,
     * -no-show-pms) name the stay too: the Opera reservation, the front office's stay, the room; the
     * desk's charges (registrar-cargo, anular-cargo), the folio line too — its id, kind, code, concept,
     * amount and currency; the desk's payments (registrar-cobro, devolver-cobro), the payment — its id, kind,
     * method, reference, amount and currency.
     */
    static final Set<String> RELAUNCH_VARIABLES = Set.of(ProcessVariables.DEFINITION_ID, ProcessVariables.HOTEL_CODE,
            ProcessVariables.LOCATOR, ProcessVariables.PARTNER_CODE, ProcessVariables.VERSION, ProcessVariables.EVENT_ID, ProcessVariables.ORIGIN,
            ProcessVariables.PMS_HOTEL_CODE, ProcessVariables.PMS_RESERVATION_ID, ProcessVariables.STAY_ID, ProcessVariables.ROOM_NUMBER,
            ProcessVariables.LINE_ID, ProcessVariables.CHARGE_KIND, ProcessVariables.CHARGE_CODE, ProcessVariables.DESCRIPTION,
            ProcessVariables.AMOUNT, ProcessVariables.CURRENCY, ProcessVariables.PAYMENT_ID, ProcessVariables.PAYMENT_KIND,
            ProcessVariables.PAYMENT_METHOD, ProcessVariables.PAYMENT_REFERENCE);

    /** The definitions whose waiters are a booking's: a relaunch names it on its span, for the booking's journey. */
    static final Set<String> BOOKING_DEFINITIONS = Set.of(Definitions.PROJECT_RESERVATION, Definitions.PROJECT_CANCELLATION,
            Definitions.REGISTER_CHECK_IN, Definitions.REGISTER_CHECK_OUT, Definitions.REGISTER_NO_SHOW_PMS,
            Definitions.REGISTER_CHARGE, Definitions.REVERSE_CHARGE, Definitions.REGISTER_PAYMENT, Definitions.REFUND_PAYMENT);

    final CauseRecordRepository causes;
    final WaiterRepository waiters;
    final WaiterCauseRepository links;
    final Outbox outbox;
    final MappingProperties properties;
    final Clock clock;

    /**
     * Registers that a process waits on these causes, opening the ones that are not open. A cause
     * opened here for the first time — or again, after it was resolved — is announced once.
     * Registering the same process twice is harmless: a retried step does exactly that.
     */
    @Transactional
    public void await(String processKey, String definitionId, String hotelCode, String subject,
                      List<Variable> variables, List<Cause> blocking) {
        await(processKey, null, definitionId, hotelCode, subject, variables, blocking);
    }

    /**
     * @param engineProcessId the engine's id of the waiting process — what discarding it cancels it
     *                        by; null when the caller does not know it
     */
    @Transactional
    public void await(String processKey, String engineProcessId, String definitionId, String hotelCode, String subject,
                      List<Variable> variables, List<Cause> blocking) {
        // In key order, as every transaction that locks several causes: two never wait on each other.
        for (var cause : blocking.stream().sorted(java.util.Comparator.comparing(Cause::key)).toList()) {
            // Locked before it is read: a resolution running now either sees this wait once it commits,
            // or has committed already and the cause reads as resolved — and is opened again here.
            causes.lock(cause.key());
            var record = causes.findById(cause.key()).orElseGet(() -> {
                var fresh = new CauseRecord();
                fresh.causeKey = cause.key();
                fresh.type = cause.type();
                fresh.hotelCode = hotelCode;
                return fresh;
            });
            if (record.status != CauseStatus.OPEN) {
                record.status = CauseStatus.OPEN;
                record.description = cause.description();
                record.openedAt = clock.instant();
                record.resolvedAt = null;
                record.resolvedBy = null;
                record.openings++;
                causes.save(record);
                announce(record);
            }
            if (!links.existsById(new WaiterCause.Key(processKey, cause.key()))) {
                links.save(new WaiterCause(processKey, cause.key()));
            }
        }
        var waiter = waiters.findById(processKey).orElseGet(Waiter::new);
        if (waiter.getStatus() == null) {
            waiter.setProcessKey(processKey);
            waiter.setDefinitionId(definitionId);
            waiter.setHotelCode(hotelCode);
            waiter.setSubject(subject);
            waiter.setVariables(variables.stream().filter(v -> RELAUNCH_VARIABLES.contains(v.name())).toList());
            waiter.setStatus(WaiterStatus.WAITING);
            waiter.setCreatedAt(clock.instant());
            waiter.setEngineProcessId(engineProcessId);
            waiters.save(waiter);
        } else if (waiter.getEngineProcessId() == null && engineProcessId != null) {
            waiter.setEngineProcessId(engineProcessId);
            waiters.save(waiter);
        }
        log.info("{} waits on {}", processKey, blocking.stream().map(Cause::key).toList());
    }

    /** A person, or something that happened, removed the cause. Every process waiting only on it resumes. */
    @Audited("Resolve cause")
    @Transactional
    public void resolve(String causeKey, String resolvedBy) {
        // Serialised with every wait on it and every other resolution of it (see await).
        causes.lock(causeKey);
        var record = causes.findById(causeKey).orElseThrow(() -> new NoSuchElementException("No cause " + causeKey));
        if (record.status == CauseStatus.RESOLVED) {
            return;
        }
        record.status = CauseStatus.RESOLVED;
        record.resolvedAt = clock.instant();
        record.resolvedBy = resolvedBy;
        causes.save(record);
        outbox.appendResolution(causeKey, resolvedBy);
        var released = 0;
        // In key order, so two resolutions that share waiters lock them in the same order.
        for (var waiter : waiters.waitingOn(causeKey).stream().sorted(java.util.Comparator.comparing(Waiter::getProcessKey)).toList()) {
            // Locked before its causes are counted: another of its causes being resolved at the same
            // time either sees this one resolved, or is seen resolved here — never neither. And it may
            // have released, or a person discarded, the process meanwhile.
            if (!waiters.lockStatus(waiter.getProcessKey()).map(WaiterStatus.WAITING.name()::equals).orElse(false)) {
                continue;
            }
            if (links.openCausesOf(waiter.getProcessKey()) == 0) {
                waiter.setStatus(WaiterStatus.RELEASED);
                waiter.setReleasedAt(clock.instant());
                signal(waiter);
                released++;
            }
        }
        log.info("Cause {} resolved by {}: {} process(es) resume", causeKey, resolvedBy, released);
    }

    /** Resolves, if open, the cause this approval removes — and, for a chain entry, every hotel's. */
    @Transactional
    public void mappingApproved(CodeType type, String hotelCode, String code, String approvedBy) {
        causes.findByTypeAndStatus(CauseType.MISSING_MAPPING, CauseStatus.OPEN).stream()
                .filter(c -> c.causeKey.endsWith(Cause.SEPARATOR + type + Cause.SEPARATOR + code))
                .filter(c -> hotelCode == null || c.causeKey.equals(Cause.missingMapping(hotelCode, type, code).key()))
                .forEach(c -> resolve(c.causeKey, approvedBy));
    }

    /** Resolves a cause if it is open, and does nothing otherwise — for steps that may run twice. */
    @Transactional
    public void resolveIfOpen(String causeKey, String resolvedBy) {
        // Locked before it is read, so whether it is open is read as last committed.
        causes.lock(causeKey);
        causes.findById(causeKey).filter(c -> c.status == CauseStatus.OPEN).ifPresent(c -> resolve(causeKey, resolvedBy));
    }

    /**
     * The resumed process asks for its successor. Idempotent: the successor's business key is
     * derived from the waiting process's, so asking twice starts it once.
     */
    @Transactional
    public String relaunch(String processKey) {
        var waiter = waiters.findById(processKey).orElseThrow(() -> new NoSuchElementException("No waiting process " + processKey));
        var successorKey = processKey + ">r";
        // The successor starts in the trace of whoever resolved the causes — an approval, for as many
        // reservations as it released — so it says which reservation it is, as the router does: the
        // booking's journey (journey-service) finds it by this.
        if (waiter.getSubject() != null && BOOKING_DEFINITIONS.contains(waiter.getDefinitionId())) {
            var span = io.opentelemetry.api.trace.Span.current();
            span.setAttribute("booking.locator", waiter.getSubject());
            if (waiter.getHotelCode() != null) {
                span.setAttribute("hotel.code", waiter.getHotelCode());
            }
            span.setAttribute("booking.event", "relaunch");
            span.setAttribute("eventconductor.business-key", successorKey);
        }
        if (waiter.getStatus() == WaiterStatus.DISCARDED) {
            // Discarded while its resume message was on its way: a person gave it up, so no successor.
            log.warn("{} resumed after it was discarded by {}: no successor started", processKey, waiter.getFinishedBy());
            return successorKey;
        }
        if (waiter.getStatus() != WaiterStatus.RELAUNCHED) {
            var variables = new java.util.ArrayList<>(waiter.getVariables());
            variables.add(new Variable(ProcessVariables.PROCESS_KEY, successorKey));
            outbox.appendToEngine(new ProcessCreationRequested(waiter.getDefinitionId(), successorKey, variables,
                    null, AuthorizationContext.SYSTEM));
            waiter.setStatus(WaiterStatus.RELAUNCHED);
            waiter.setFinishedAt(clock.instant());
            waiters.save(waiter);
            log.info("{} resumed: {} started", processKey, successorKey);
        }
        return successorKey;
    }

    /**
     * What discarding a process did.
     *
     * @param engineCancelRequested whether the engine was asked to cancel the process; when not, it
     *                              did not know its id, and it is cancelled by hand at {@code adminProcessesUrl}
     * @param causesLeftUnwaited    the open causes this process waited on that no process waits on now —
     *                              for whoever discarded it to resolve, if they no longer matter
     */
    public record Discarded(String processKey, boolean engineCancelRequested, String adminProcessesUrl,
                            List<String> causesLeftUnwaited) {
    }

    /**
     * The one way out that is not resolving the causes (F012): a person gives up on the process. It
     * is not resumed, not signalled again, not relaunched; and the engine is asked to cancel it, so
     * it does not stay RUNNING on a wait nothing will end.
     *
     * <p>{@code discardedBy} goes last: it is who the audit names.
     *
     * @throws IllegalStateException if the process already resumed and started its successor
     */
    @Audited("Discard process")
    @Transactional
    public Discarded discard(String processKey, String reason, String discardedBy) {
        var waiter = waiters.findById(processKey).orElseThrow(() -> new NoSuchElementException("No waiting process " + processKey));
        if (waiter.getStatus() == WaiterStatus.RELAUNCHED) {
            throw new IllegalStateException(processKey + " already resumed and started its successor: nothing to discard");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why the process is discarded");
        }
        var cancel = waiter.getEngineProcessId() != null;
        if (waiter.getStatus() != WaiterStatus.DISCARDED) {
            waiter.setStatus(WaiterStatus.DISCARDED);
            waiter.setFinishedAt(clock.instant());
            waiter.setFinishedBy(discardedBy);
            waiter.setReason(reason.strip());
            waiters.save(waiter);
            if (cancel) {
                // The engine's own operator cancellation, addressed as its UI addresses it: by id.
                outbox.appendToEngine(new ProcessCancellationRequested(processKey, waiter.getEngineProcessId()));
            }
            log.warn("{} discarded by {} ({}); engine cancellation {}", processKey, discardedBy, reason,
                    cancel ? "requested" : "left to Admin → Processes");
        }
        var unwaited = links.findByProcessKey(processKey).stream().map(l -> l.causeKey)
                .filter(key -> causes.findById(key).map(c -> c.status == CauseStatus.OPEN).orElse(false))
                .filter(key -> waiters.countWaitingOn(key) == 0)
                .toList();
        return new Discarded(processKey, cancel, properties.adminProcessesUrl(), unwaited);
    }

    /** Discards every process waiting on this cause — or released by it and not answering — for one reason; see {@link #discard}. */
    @Audited("Discard processes waiting on a cause")
    @Transactional
    public List<Discarded> discardAllWaitingOn(String causeKey, String reason, String discardedBy) {
        causes.findById(causeKey).orElseThrow(() -> new NoSuchElementException("No cause " + causeKey));
        return waiters.pendingOn(causeKey).stream().map(w -> discard(w.getProcessKey(), reason, discardedBy)).toList();
    }

    /**
     * Resends the resume message to released processes that have not answered yet — for as long as
     * {@code mapping.resend-for} after their release. Never to a discarded one: it is not RELEASED.
     */
    @Scheduled(fixedDelayString = "${mapping.resend-check:10s}")
    @Transactional
    public void resendSilent() {
        var now = clock.instant();
        for (var waiter : waiters.releasedAndSilentSince(now.minus(properties.resendAfter()), now.minus(properties.resendFor()))) {
            log.debug("Resending the resume message to {}", waiter.getProcessKey());
            signal(waiter);
        }
    }

    private void signal(Waiter waiter) {
        outbox.appendToEngine(new MessageReceived(Definitions.CAUSES_RESOLVED_MESSAGE, waiter.getProcessKey(), List.of()));
        waiter.setLastSignalAt(clock.instant());
        waiters.save(waiter);
    }

    private void announce(CauseRecord cause) {
        outbox.appendNotification(new NotificationRequested(
                UUID.randomUUID().toString(),
                cause.type == CauseType.PMS_REJECTED ? NotificationType.PMS_REJECTED : NotificationType.CAUSE_OPENED,
                cause.hotelCode,
                cause.causeKey,
                "Processes waiting: " + cause.description,
                cause.description + ". Processes that need it wait until it is resolved; resolving it resumes all of them.",
                properties.consoleUrl() + "/mapping/causes",
                "cause-opened:" + cause.causeKey + ":" + cause.openings,
                clock.instant()));
    }
}
