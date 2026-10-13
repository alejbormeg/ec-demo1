package io.mateu.ecdemo1.integration.model.frontoffice;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What happened at a hotel's reception, as the front office says it: a guest checked in, checked out,
 * or never came; the desk charged something to the stay's folio, or took a charge back. The PMS is the master of the stay, so what the desk did goes UP to it: the pms-fo
 * integration takes these from the {@code front-office-events} topic and starts the process that
 * records it in the PMS («registrar-checkin», «registrar-checkout», «registrar-no-show-pms»). Written
 * to the front office's outbox in the transaction of the desk's decision; the consumer deduplicates on
 * {@link #eventId()}, and the engine on the process key, so taking one twice records it once.
 *
 * <p>Every event names the stay both ways: the front office's own id and hotel (the CRS's code), the
 * CRS's locator when the reservation came from the CRS (null for one born in the PMS, or a walk-in the
 * CRS has not booked yet), and the PMS reservation when the front office has it linked (null for a
 * walk-in that has not reached the PMS yet: the PMS side finds it by the CRS locator, or waits for it).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FrontOfficeEvent.GuestCheckedIn.class, name = "guest-checked-in"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.GuestCheckedOut.class, name = "guest-checked-out"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.NoShowReported.class, name = "no-show-reported"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.ChargePosted.class, name = "charge-posted"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.ChargeVoided.class, name = "charge-voided"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.StayClosed.class, name = "stay-closed"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.PaymentTaken.class, name = "payment-taken"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.PaymentRefunded.class, name = "payment-refunded"),
        @JsonSubTypes.Type(value = FrontOfficeEvent.CompanyChanged.class, name = "company-changed"),
})
public sealed interface FrontOfficeEvent {

    /** The topic the front office publishes them on. */
    String TOPIC = "front-office-events";

    /** Unique per event; the consumer deduplicates on it. */
    String eventId();

    /** The front office's hotel — the CRS's code of it (MRU01). */
    String hotelCode();

    /** The front office's stay. */
    String stayId();

    /** The CRS's locator, when the reservation came from the CRS. */
    String crsLocator();

    /** The PMS property the front office consumes. */
    String pmsHotelCode();

    /** The PMS reservation, when the front office has the stay linked to it. */
    String pmsReservationId();

    /** The Kafka key: what is said about one stay stays in order. */
    @JsonIgnore
    default String key() {
        return hotelCode() + "/" + stayId();
    }

    /**
     * The desk checked the guests in.
     *
     * @param roomNumber the room the desk gave them; null if the desk did not choose one (the PMS's
     *                   assignment stands, or the PMS suggests one)
     * @param pax        how many people arrived
     */
    record GuestCheckedIn(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                          String pmsHotelCode, String pmsReservationId, String roomNumber, int pax, String by)
            implements FrontOfficeEvent {
    }

    /** The desk checked the guests out: the room is free, the folio closed at the desk. */
    record GuestCheckedOut(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                           String pmsHotelCode, String pmsReservationId, String roomNumber, String by)
            implements FrontOfficeEvent {
    }

    /**
     * Nobody of the reservation arrived: the desk marked every guest as a no-show. The PMS records it,
     * and reports it to the CRS, which applies its fee.
     */
    record NoShowReported(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                          String pmsHotelCode, String pmsReservationId, int pax, String by)
            implements FrontOfficeEvent {
    }

    /** What a charge the desk puts on a stay's folio is: the PMS books each kind with its own transaction code. */
    enum ChargeKind {
        /** An extra contracted at the check-in (a transfer, a dinner…): its code is the add-on's. */
        ADD_ON,
        /** The late check-out. */
        LATE_CHECK_OUT,
        /** A consumption posted during the stay from the desk's charge catalogue (minibar, room service…): its code is the catalogue's. */
        CONSUMPTION
    }

    /**
     * The desk charged something to the stay's folio — a late check-out, an extra, a consumption. The PMS
     * is the master of the folio: the pms-fo integration posts it to the reservation's folio in the PMS
     * («registrar-cargo»), once per folio line: {@code lineId} is the idempotency key, the PMS keeps it
     * as the posting's reference. The stay's accommodation is not one: the PMS charges it itself.
     *
     * @param lineId      the front office's folio line: unique, and what a void names
     * @param kind        what the charge is
     * @param code        the front office's code of it — the catalogue's, the add-on's; null for a late check-out
     * @param description the line's concept, as the folio shows it
     * @param amount      what it costs, in the property's currency
     * @param currency    ISO 4217; null for the PMS property's own
     */
    record ChargePosted(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                        String pmsHotelCode, String pmsReservationId, String lineId, ChargeKind kind, String code,
                        String description, BigDecimal amount, String currency, String by)
            implements FrontOfficeEvent {
    }

    /**
     * The desk took back a charge of the stay's folio — voided, or refunded: the line no longer counts.
     * The PMS reverses its posting («anular-cargo»): the same amount, negative, with the same
     * transaction code. It names the line as its {@link ChargePosted} did, with the same figures.
     */
    record ChargeVoided(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                        String pmsHotelCode, String pmsReservationId, String lineId, ChargeKind kind, String code,
                        String description, BigDecimal amount, String currency, String by)
            implements FrontOfficeEvent {
    }

    /**
     * The desk took a payment on the stay's account — of what is owed, or an advance — and it was
     * captured. The PMS is the master of the folio: the pms-fo integration posts it to the reservation's
     * folio in the PMS («registrar-cobro»), once per payment: {@code paymentId} is the idempotency key, the
     * PMS keeps it in the posting's reference. Only captured payments: a pending pay link or a declined card
     * is not one.
     *
     * @param paymentId the front office's payment: unique, and what a refund names
     * @param kind      PAYMENT or DEPOSIT (an advance)
     * @param method    how the guest paid: CASH, CARD_PINPAD, PAY_LINK, TRANSFER, MANUAL
     * @param amount    what was paid, positive
     * @param currency  ISO 4217; null for the PMS property's own
     * @param reference the desk's reference of it (the terminal's authorization…); may be null
     */
    record PaymentTaken(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                        String pmsHotelCode, String pmsReservationId, String paymentId, String kind, String method,
                        BigDecimal amount, String currency, String reference, String by) implements FrontOfficeEvent {
    }

    /**
     * The desk gave back a captured payment: the PMS posts the same payment, negative, against the
     * original («devolver-cobro»). It names the payment as its {@link PaymentTaken} did, with the same figures.
     */
    record PaymentRefunded(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                           String pmsHotelCode, String pmsReservationId, String paymentId, String kind, String method,
                           BigDecimal amount, String currency, String reference, String by) implements FrontOfficeEvent {
    }

    /**
     * The desk set the company of the stay — who is billed when neither the agency nor the guest is —, or
     * took it away: the PMS attaches the company's profile to the reservation and routes the stay's
     * accommodation and packages to it, or detaches it («asignar-empresa»).
     *
     * @param companyCode the company, a partner of the ERP (its code); null when the desk took it away
     * @param companyName its name, as the ERP has it; null when taken away
     */
    record CompanyChanged(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                          String pmsHotelCode, String pmsReservationId, String companyCode, String companyName,
                          String by) implements FrontOfficeEvent {
    }

    /**
     * A stay is over: the guests checked out and the desk closed it (CM-R13 of the HLA). It carries
     * everything a customer's stay history needs — the dates, the room, who stayed, what they spent —
     * so that whoever keeps that history does not depend on the order of the events before it.
     * Published at the check-out, after {@link GuestCheckedOut}, even when the stay is not linked to
     * the PMS: the history is the chain's, not the PMS's.
     *
     * @param guests   who stayed, holder first: each with the id the front office knows them by — the
     *                 MDM's customer code ({@code C-…}) when it has one, else its own ({@code pax-2},
     *                 {@code opera-…}, {@code wi-…})
     * @param charges  what the stay spent at the desk, per kind of charge, voided lines left out; the
     *                 accommodation is not here — it is not a consumption
     * @param total    the sum of {@code charges}
     * @param currency ISO 4217; null for the PMS property's own
     */
    record StayClosed(String eventId, Instant at, String hotelCode, String stayId, String crsLocator,
                      String pmsHotelCode, String pmsReservationId, LocalDate arrival, LocalDate departure,
                      int nights, String roomNumber, String roomType, String board, List<StayGuest> guests,
                      List<ChargeTotal> charges, BigDecimal total, String currency)
            implements FrontOfficeEvent {
    }

    /** One guest of a closed stay: the id the front office knows them by, and whether they held the reservation. */
    record StayGuest(String customerId, boolean holder) {
    }

    /** What a closed stay spent on one kind of charge. */
    record ChargeTotal(ChargeKind kind, BigDecimal amount) {
    }
}
