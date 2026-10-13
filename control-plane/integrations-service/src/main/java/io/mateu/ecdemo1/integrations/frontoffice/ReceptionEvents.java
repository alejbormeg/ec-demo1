package io.mateu.ecdemo1.integrations.frontoffice;

import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent;
import io.mateu.ecdemo1.integration.model.process.Definitions;
import io.mateu.ecdemo1.integration.model.process.ProcessVariables;
import io.mateu.ecdemo1.integrations.lifecycle.Writes;
import io.mateu.ecdemo1.integrations.outbox.Outbox;
import io.mateu.ecdemo1.integrations.store.FoIntegrationStatus;
import io.mateu.ecdemo1.integrations.store.FrontOfficeIntegrationRepository;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.security.AuthorizationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The reception, up to the PMS — the master of the stay (pms-fo). The front office says what its desk
 * did ({@code front-office-events}); if the property feeds that front office and its integration is
 * active, the process that records it in the PMS starts: «registrar-checkin», «registrar-checkout»,
 * «registrar-no-show-pms» (which goes on to the CRS, the master of the sale, for its fee), and, for the
 * desk's charges, «registrar-cargo» and «anular-cargo» (onto the PMS's folio, per folio line), and its
 * payments, «registrar-cobro» and «devolver-cobro» (per payment).
 *
 * <p>One process per reservation and operation — a check-in, per room: its business key names them, and
 * the engine ignores a key it already has — the dedup, so an event taken twice records once. The key
 * names the stay's reservation as the booking's journey finds it: {@code <definition>:<hotel>/<locator>}
 * (and {@code :<room>} for a check-in) — the CRS's hotel and locator, or, for a reservation born in the
 * PMS, the property and the PMS's id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReceptionEvents {

    final FrontOfficeIntegrationRepository integrations;
    final Outbox outbox;
    final Writes writes;

    /** The process key started for it, or null when there is none to start. */
    public String on(FrontOfficeEvent event) {
        if (event instanceof FrontOfficeEvent.StayClosed) {
            // The stay's summary for the customer's history (customer-history): the PMS already heard of the
            // check-out from GuestCheckedOut, and there is nothing in it for the PMS to record.
            return null;
        }
        var pmsHotel = event.pmsHotelCode();
        if (pmsHotel == null) {
            log.warn("{} of stay {}/{} names no PMS property: nothing to record", event.getClass().getSimpleName(),
                    event.hotelCode(), event.stayId());
            return null;
        }
        var active = integrations.findFirstByPmsHotelCodeAndStatusNot(pmsHotel, FoIntegrationStatus.DECOMMISSIONED)
                .filter(i -> i.is(FoIntegrationStatus.ACTIVE));
        if (active.isEmpty()) {
            log.info("{} of stay {}/{}: no active front office integration for {}; the PMS is not told",
                    event.getClass().getSimpleName(), event.hotelCode(), event.stayId(), pmsHotel);
            return null;
        }
        var crsLocator = event.crsLocator();
        var pmsReservationId = event.pmsReservationId();
        if (blank(crsLocator) && blank(pmsReservationId)) {
            // A walk-in the CRS has not booked yet: the front office tells it again once the booking is
            // back from the PMS (and so linked to its reservation).
            log.info("{} of stay {}/{}: neither in the CRS nor in the PMS yet; nothing to record", event.getClass().getSimpleName(),
                    event.hotelCode(), event.stayId());
            return null;
        }
        var hotelCode = blank(crsLocator) ? pmsHotel : event.hotelCode();
        var locator = blank(crsLocator) ? pmsReservationId : crsLocator;
        var definition = switch (event) {
            case FrontOfficeEvent.GuestCheckedIn ignored -> Definitions.REGISTER_CHECK_IN;
            case FrontOfficeEvent.GuestCheckedOut ignored -> Definitions.REGISTER_CHECK_OUT;
            case FrontOfficeEvent.NoShowReported ignored -> Definitions.REGISTER_NO_SHOW_PMS;
            case FrontOfficeEvent.ChargePosted ignored -> Definitions.REGISTER_CHARGE;
            case FrontOfficeEvent.ChargeVoided ignored -> Definitions.REVERSE_CHARGE;
            case FrontOfficeEvent.PaymentTaken ignored -> Definitions.REGISTER_PAYMENT;
            case FrontOfficeEvent.PaymentRefunded ignored -> Definitions.REFUND_PAYMENT;
            case FrontOfficeEvent.CompanyChanged ignored -> Definitions.ASSIGN_COMPANY;
            case FrontOfficeEvent.StayClosed ignored -> throw new IllegalStateException("taken above: not the PMS's");
        };
        // One check-in per room: the desk that picks another room after Opera refused one starts it again
        // with the new room (and the one that waits on the refusal is resolved when this one gets in). One
        // charge, and one void, per folio line.
        var key = definition + ":" + hotelCode + "/" + locator + switch (event) {
            case FrontOfficeEvent.GuestCheckedIn in -> ":" + (blank(in.roomNumber()) ? "-" : in.roomNumber());
            case FrontOfficeEvent.ChargePosted charge -> ":" + charge.lineId();
            case FrontOfficeEvent.ChargeVoided voided -> ":" + voided.lineId();
            case FrontOfficeEvent.PaymentTaken p -> ":" + p.paymentId();
            case FrontOfficeEvent.PaymentRefunded p -> ":" + p.paymentId();
            // each change of the stay's company its own process; the reservation's lock keeps them in order
            case FrontOfficeEvent.CompanyChanged c -> ":" + c.eventId();
            default -> "";
        };
        var variables = new ArrayList<Variable>(List.of(
                new Variable(ProcessVariables.PROCESS_KEY, key),
                new Variable(ProcessVariables.DEFINITION_ID, definition),
                new Variable(ProcessVariables.HOTEL_CODE, hotelCode),
                new Variable(ProcessVariables.LOCATOR, locator),
                new Variable(ProcessVariables.PMS_HOTEL_CODE, pmsHotel),
                new Variable(ProcessVariables.STAY_ID, event.stayId()),
                new Variable(ProcessVariables.EVENT_ID, event.eventId()),
                new Variable(ProcessVariables.ORIGIN, "front-office:" + event.hotelCode()),
                new Variable(ProcessVariables.INTEGRATION_ID, active.get().id)));
        if (!blank(pmsReservationId)) {
            variables.add(new Variable(ProcessVariables.PMS_RESERVATION_ID, pmsReservationId));
        }
        if (event instanceof FrontOfficeEvent.GuestCheckedIn in && !blank(in.roomNumber())) {
            variables.add(new Variable(ProcessVariables.ROOM_NUMBER, in.roomNumber()));
        }
        switch (event) {
            case FrontOfficeEvent.ChargePosted c -> charge(variables, c.lineId(), c.kind(), c.code(), c.description(),
                    c.amount(), c.currency());
            case FrontOfficeEvent.ChargeVoided v -> charge(variables, v.lineId(), v.kind(), v.code(), v.description(),
                    v.amount(), v.currency());
            case FrontOfficeEvent.PaymentTaken p -> payment(variables, p.paymentId(), p.kind(), p.method(), p.amount(),
                    p.currency(), p.reference());
            case FrontOfficeEvent.PaymentRefunded p -> payment(variables, p.paymentId(), p.kind(), p.method(), p.amount(),
                    p.currency(), p.reference());
            case FrontOfficeEvent.CompanyChanged c -> {
                if (!blank(c.companyCode())) {
                    variables.add(new Variable(ProcessVariables.COMPANY_CODE, c.companyCode()));
                }
                if (!blank(c.companyName())) {
                    variables.add(new Variable(ProcessVariables.COMPANY_NAME, c.companyName()));
                }
            }
            default -> {
            }
        }
        // What the booking's journey (journey-service) finds this trace by, on the span that starts it.
        var span = io.opentelemetry.api.trace.Span.current();
        span.setAttribute("booking.locator", locator);
        span.setAttribute("hotel.code", hotelCode);
        span.setAttribute("booking.event", switch (event) {
            case FrontOfficeEvent.GuestCheckedIn ignored -> "check-in";
            case FrontOfficeEvent.GuestCheckedOut ignored -> "check-out";
            case FrontOfficeEvent.NoShowReported ignored -> "no-show";
            case FrontOfficeEvent.ChargePosted ignored -> "charge";
            case FrontOfficeEvent.ChargeVoided ignored -> "charge-void";
            case FrontOfficeEvent.PaymentTaken ignored -> "payment";
            case FrontOfficeEvent.PaymentRefunded ignored -> "payment-refund";
            case FrontOfficeEvent.CompanyChanged ignored -> "company";
            case FrontOfficeEvent.StayClosed ignored -> "stay-closed";
        });
        span.setAttribute("eventconductor.business-key", key);
        writes.write(() -> {
            outbox.appendToEngine(new ProcessCreationRequested(definition, key, variables, null, AuthorizationContext.SYSTEM));
            return key;
        });
        log.info("{} of stay {}/{}: «{}» started ({})", event.getClass().getSimpleName(), event.hotelCode(), event.stayId(),
                definition, key);
        return key;
    }

    /** The folio line a charge process posts or reverses: its id, what it is, its concept and amount. */
    static void charge(List<Variable> variables, String lineId, FrontOfficeEvent.ChargeKind kind, String code,
                       String description, java.math.BigDecimal amount, String currency) {
        variables.add(new Variable(ProcessVariables.LINE_ID, lineId));
        if (kind != null) {
            variables.add(new Variable(ProcessVariables.CHARGE_KIND, kind.name()));
        }
        if (!blank(code)) {
            variables.add(new Variable(ProcessVariables.CHARGE_CODE, code));
        }
        if (!blank(description)) {
            variables.add(new Variable(ProcessVariables.DESCRIPTION, description));
        }
        if (amount != null) {
            variables.add(new Variable(ProcessVariables.AMOUNT, amount.toPlainString()));
        }
        if (!blank(currency)) {
            variables.add(new Variable(ProcessVariables.CURRENCY, currency));
        }
    }

    static void payment(List<Variable> variables, String paymentId, String kind, String method, java.math.BigDecimal amount,
                        String currency, String reference) {
        variables.add(new Variable(ProcessVariables.PAYMENT_ID, paymentId));
        if (!blank(kind)) {
            variables.add(new Variable(ProcessVariables.PAYMENT_KIND, kind));
        }
        if (!blank(method)) {
            variables.add(new Variable(ProcessVariables.PAYMENT_METHOD, method));
        }
        if (amount != null) {
            variables.add(new Variable(ProcessVariables.AMOUNT, amount.toPlainString()));
        }
        if (!blank(currency)) {
            variables.add(new Variable(ProcessVariables.CURRENCY, currency));
        }
        if (!blank(reference)) {
            variables.add(new Variable(ProcessVariables.PAYMENT_REFERENCE, reference));
        }
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
