package io.mateu.ecdemo1.pmsintegration.worker;

import io.mateu.ecdemo1.pmsintegration.ohip.PmsTransientException;
import io.mateu.workflow.worker.api.ReplyNotAcceptedException;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskHandler;
import io.mateu.workflow.worker.api.TaskRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * The tasks the PMS adapter serves, each bound to its contract in ec-definitions
 * ({@code definitions/tasks/<id>.ectask}): the engine's worker runtime (worker-kafka) collects these
 * registrations and dispatches every {@code <id>@<version>} it receives on the {@value #TOPIC} topic
 * to the handler here.
 *
 * <p>A step that fails is answered as a failure and the engine retries it with backoff, as it always
 * was; every failure also goes on the {@link RetryWatch}, which raises the alarm when it lasts, and a
 * success takes it off.
 */
@Configuration
@Slf4j
public class PmsTasks {

    public static final String TOPIC = "pms-integration";

    public static final String ENSURE_GUEST_PROFILE = "ensure-guest-profile";
    public static final String UPSERT_RESERVATION = "upsert-reservation";
    public static final String CANCEL_RESERVATION = "cancel-reservation";
    public static final String ENSURE_PARTNER_PROFILE = "ensure-partner-profile";
    public static final String PROJECT_STAY = "project-stay";
    public static final String ASSIGN_ROOM = "assign-room";
    public static final String CHECK_IN_RESERVATION = "check-in-reservation";
    public static final String CHECK_OUT_RESERVATION = "check-out-reservation";
    public static final String FETCH_INVOICE = "fetch-invoice";
    public static final String RECORD_NO_SHOW = "record-no-show";
    public static final String POST_CHARGE = "post-charge";
    public static final String REVERSE_CHARGE = "reverse-charge";
    public static final String POST_PAYMENT = "post-payment";
    public static final String REFUND_PAYMENT = "refund-payment";
    public static final String NEW_OPERA_CONTEXT = "new-opera-context";

    /** new-opera-context's input: the reset-demo process asking. */
    public record ContextTask(String processKey) {
    }

    @Bean
    public TaskRegistration<TaskHandlers.ReservationTask, TaskHandlers.GuestProfile> ensureGuestProfileTask(
            TaskHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(ENSURE_GUEST_PROFILE, 1, TOPIC, TaskHandlers.ReservationTask.class,
                TaskHandlers.GuestProfile.class, watched(watch, handlers::ensureGuestProfile,
                        TaskHandlers.ReservationTask::hotelCode, TaskHandlers.ReservationTask::locator));
    }

    @Bean
    public TaskRegistration<TaskHandlers.ReservationTask, TaskHandlers.Write> upsertReservationTask(
            TaskHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(UPSERT_RESERVATION, 1, TOPIC, TaskHandlers.ReservationTask.class,
                TaskHandlers.Write.class, watched(watch, handlers::upsertReservation,
                        TaskHandlers.ReservationTask::hotelCode, TaskHandlers.ReservationTask::locator));
    }

    @Bean
    public TaskRegistration<TaskHandlers.ReservationTask, TaskHandlers.Write> cancelReservationTask(
            TaskHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(CANCEL_RESERVATION, 1, TOPIC, TaskHandlers.ReservationTask.class,
                TaskHandlers.Write.class, watched(watch, handlers::cancelReservation,
                        TaskHandlers.ReservationTask::hotelCode, TaskHandlers.ReservationTask::locator));
    }

    @Bean
    public TaskRegistration<TaskHandlers.PartnerTask, TaskHandlers.PartnerProfiled> ensurePartnerProfileTask(
            TaskHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(ENSURE_PARTNER_PROFILE, 1, TOPIC, TaskHandlers.PartnerTask.class,
                TaskHandlers.PartnerProfiled.class, watched(watch, handlers::ensurePartnerProfile,
                        input -> null, TaskHandlers.PartnerTask::partnerCode));
    }

    @Bean
    public TaskRegistration<TaskHandlers.StayTask, Void> projectStayTask(TaskHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(PROJECT_STAY, 1, TOPIC, TaskHandlers.StayTask.class, Void.class,
                watched(watch, handlers::projectStay, TaskHandlers.StayTask::pmsHotelCode,
                        input -> input.pmsHotelCode() + "/" + input.pmsReservationId()));
    }

    // ── the reception, up to the PMS (pms-fo) ──────────────────────────────────────────────────────

    @Bean
    public TaskRegistration<ReceptionHandlers.ReceptionTask, ReceptionHandlers.RoomAssigned> assignRoomTask(
            ReceptionHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(ASSIGN_ROOM, 1, TOPIC, ReceptionHandlers.ReceptionTask.class,
                ReceptionHandlers.RoomAssigned.class, watched(watch, handlers::assignRoom,
                        ReceptionHandlers.ReceptionTask::pmsHotelCode, ReceptionHandlers.ReceptionTask::stayId));
    }

    @Bean
    public TaskRegistration<ReceptionHandlers.ReceptionTask, ReceptionHandlers.CheckedIn> checkInReservationTask(
            ReceptionHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(CHECK_IN_RESERVATION, 1, TOPIC, ReceptionHandlers.ReceptionTask.class,
                ReceptionHandlers.CheckedIn.class, watched(watch, handlers::checkIn,
                        ReceptionHandlers.ReceptionTask::pmsHotelCode, ReceptionHandlers.ReceptionTask::stayId));
    }

    @Bean
    public TaskRegistration<ReceptionHandlers.ReceptionTask, ReceptionHandlers.CheckedOut> checkOutReservationTask(
            ReceptionHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(CHECK_OUT_RESERVATION, 1, TOPIC, ReceptionHandlers.ReceptionTask.class,
                ReceptionHandlers.CheckedOut.class, watched(watch, handlers::checkOut,
                        ReceptionHandlers.ReceptionTask::pmsHotelCode, ReceptionHandlers.ReceptionTask::stayId));
    }

    @Bean
    public TaskRegistration<ReceptionHandlers.InvoiceTask, ReceptionHandlers.InvoiceFetched> fetchInvoiceTask(
            ReceptionHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(FETCH_INVOICE, 1, TOPIC, ReceptionHandlers.InvoiceTask.class,
                ReceptionHandlers.InvoiceFetched.class, watched(watch, handlers::fetchInvoice,
                        ReceptionHandlers.InvoiceTask::pmsHotelCode, ReceptionHandlers.InvoiceTask::stayId));
    }

    @Bean
    public TaskRegistration<ReceptionHandlers.ReceptionTask, ReceptionHandlers.NoShowRecorded> recordNoShowTask(
            ReceptionHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(RECORD_NO_SHOW, 1, TOPIC, ReceptionHandlers.ReceptionTask.class,
                ReceptionHandlers.NoShowRecorded.class, watched(watch, handlers::recordNoShow,
                        ReceptionHandlers.ReceptionTask::pmsHotelCode, ReceptionHandlers.ReceptionTask::stayId));
    }

    // ── the company billed, onto Opera's reservation (pms-fo) ──────────────────────────────────────

    @Bean
    public TaskRegistration<CompanyHandlers.CompanyTask, CompanyHandlers.CompanyAssigned> assignCompanyTask(
            CompanyHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(CompanyHandlers.ASSIGN_COMPANY, 1, TOPIC, CompanyHandlers.CompanyTask.class,
                CompanyHandlers.CompanyAssigned.class, watched(watch, handlers::assignCompany,
                        CompanyHandlers.CompanyTask::pmsHotelCode, CompanyHandlers.CompanyTask::stayId));
    }

    // ── the desk's charges, onto Opera's folio (pms-fo) ────────────────────────────────────────────

    @Bean
    public TaskRegistration<ChargeHandlers.ChargeTask, ChargeHandlers.ChargePosted> postChargeTask(
            ChargeHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(POST_CHARGE, 1, TOPIC, ChargeHandlers.ChargeTask.class,
                ChargeHandlers.ChargePosted.class, watched(watch, handlers::postCharge,
                        ChargeHandlers.ChargeTask::pmsHotelCode, input -> input.stayId() + "/" + input.lineId()));
    }

    @Bean
    public TaskRegistration<ChargeHandlers.ChargeTask, ChargeHandlers.ChargeReversed> reverseChargeTask(
            ChargeHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(REVERSE_CHARGE, 1, TOPIC, ChargeHandlers.ChargeTask.class,
                ChargeHandlers.ChargeReversed.class, watched(watch, handlers::reverseCharge,
                        ChargeHandlers.ChargeTask::pmsHotelCode, input -> input.stayId() + "/" + input.lineId()));
    }

    // ── the desk's payments, onto Opera's folio (pms-fo) ───────────────────────────────────────────

    @Bean
    public TaskRegistration<PaymentHandlers.PaymentTask, PaymentHandlers.PaymentPosted> postPaymentTask(
            PaymentHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(POST_PAYMENT, 1, TOPIC, PaymentHandlers.PaymentTask.class,
                PaymentHandlers.PaymentPosted.class, watched(watch, handlers::postPayment,
                        PaymentHandlers.PaymentTask::pmsHotelCode, input -> input.stayId() + "/" + input.paymentId()));
    }

    @Bean
    public TaskRegistration<PaymentHandlers.PaymentTask, PaymentHandlers.PaymentRefunded> refundPaymentTask(
            PaymentHandlers handlers, RetryWatch watch) {
        return new TaskRegistration<>(REFUND_PAYMENT, 1, TOPIC, PaymentHandlers.PaymentTask.class,
                PaymentHandlers.PaymentRefunded.class, watched(watch, handlers::refundPayment,
                        PaymentHandlers.PaymentTask::pmsHotelCode, input -> input.stayId() + "/" + input.paymentId()));
    }

    // ── the demo's reset (reset-demo) ───────────────────────────────────────────────────────────────

    /** A new Opera context for the demo's next run, taken at once and kept in the ec-demo-run ConfigMap. */
    @Bean
    public TaskRegistration<ContextTask, io.mateu.ecdemo1.pmsintegration.demo.NewOperaContext.Output> newOperaContextTask(
            io.mateu.ecdemo1.pmsintegration.demo.NewOperaContext contexts) {
        return new TaskRegistration<>(NEW_OPERA_CONTEXT, 1, TOPIC, ContextTask.class,
                io.mateu.ecdemo1.pmsintegration.demo.NewOperaContext.Output.class, (input, context) -> {
                    try {
                        return contexts.renew(input == null ? null : input.processKey());
                    } catch (RuntimeException e) {
                        log.warn("Step {} of {} failed: {}", context.stepId(), context.processId(), e.getMessage());
                        throw new TaskFailure(e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                });
    }

    /**
     * The handler, with the failure it gives the engine what it always was — Opera not answering is its
     * message, anything else the exception — and the {@link RetryWatch} told of every outcome.
     */
    static <I, O> TaskHandler<I, O> watched(RetryWatch watch, TaskHandler<I, O> handler, Function<I, String> hotel,
                                            Function<I, String> subject) {
        return (input, context) -> {
            try {
                var output = handler.handle(input, context);
                watch.succeeded(context.processId(), context.stepId());
                return output;
            } catch (TaskFailure | ReplyNotAcceptedException e) {
                throw e;
            } catch (PmsTransientException e) {
                log.warn("Step {} of {}: Opera not available, the engine will retry: {}", context.stepId(),
                        context.processId(), e.getMessage());
                watch.failed(context.processId(), context.stepId(), hotel.apply(input), subject.apply(input), e.getMessage());
                throw new TaskFailure(e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Step {} of {} failed: {}", context.stepId(), context.processId(), e.toString());
                watch.failed(context.processId(), context.stepId(), hotel.apply(input), subject.apply(input), e.toString());
                throw new TaskFailure(e.toString());
            }
        };
    }
}
