package io.mateu.ecdemo1.integration.model.process;

/**
 * The names of the process variables the integration's definitions and workers share. A process
 * carries references, never the reservation: whoever needs the data reads it again (HLA, "Datos
 * personales y retención").
 */
public final class ProcessVariables {

    public static final String HOTEL_CODE = "hotelCode";
    public static final String LOCATOR = "locator";
    public static final String PARTNER_CODE = "partnerCode";
    public static final String VERSION = "version";
    public static final String EVENT_ID = "eventId";
    /** The process's own correlation key: what the wait for its causes is correlated by. */
    public static final String PROCESS_KEY = "processKey";
    public static final String DEFINITION_ID = "definitionId";
    /**
     * Who started the process, when it was not a change in the CRS: {@code backfill:<run>} for a
     * reservation a backfill projects. A backfill runs before the integration is active, so its
     * processes do not wait for the activation.
     */
    public static final String ORIGIN = "origin";
    public static final String INTEGRATION_ID = "integrationId";

    public static final String PREPARE_OUTCOME = "prepareOutcome";
    public static final String PROFILE_OUTCOME = "profileOutcome";
    public static final String WRITE_OUTCOME = "writeOutcome";
    public static final String GUEST_PROFILE_ID = "guestProfileId";
    /** The holder's customer code in the MDM, stamped on the guest profile; absent when the MDM did not answer. */
    public static final String CUSTOMER_ID = "customerId";
    public static final String PMS_RESERVATION_ID = "pmsReservationId";
    /** The PMS property a process is about, when it is not a CRS hotel's: the pms-fo integration's. */
    public static final String PMS_HOTEL_CODE = "pmsHotelCode";
    public static final String PMS_PROFILE_IDS = "pmsProfileIds";
    public static final String CAUSES = "causes";
    /** The front office's stay a reception process is about (registrar-checkin, -checkout, -no-show-pms). */
    public static final String STAY_ID = "stayId";
    /** The room the desk gave the guests at the check-in; absent when it chose none. */
    public static final String ROOM_NUMBER = "roomNumber";
    public static final String ROOM_OUTCOME = "roomOutcome";
    public static final String CHECK_IN_OUTCOME = "checkInOutcome";
    public static final String CHECK_OUT_OUTCOME = "checkOutOutcome";
    public static final String NO_SHOW_OUTCOME = "noShowOutcome";
    /** The front office's folio line a charge process is about (registrar-cargo, anular-cargo): its idempotency key. */
    public static final String LINE_ID = "lineId";
    /** What the charge is: ADD_ON, LATE_CHECK_OUT, CONSUMPTION. */
    public static final String CHARGE_KIND = "chargeKind";
    /** The front office's code of the charge (its catalogue's, its add-on's); absent for a late check-out. */
    public static final String CHARGE_CODE = "chargeCode";
    /** The charge's amount, as a plain decimal ("50.00"). */
    public static final String AMOUNT = "amount";
    /** ISO 4217; absent for the PMS property's own. */
    public static final String CURRENCY = "currency";
    /** The folio line's concept. */
    public static final String DESCRIPTION = "description";
    public static final String CHARGE_OUTCOME = "chargeOutcome";
    /** The front office's payment a payment process is about (registrar-cobro, devolver-cobro): its idempotency key. */
    public static final String PAYMENT_ID = "paymentId";
    /** PAYMENT or DEPOSIT. */
    public static final String PAYMENT_KIND = "paymentKind";
    /** How the guest paid, in the front office's words: CASH, CARD_PINPAD, PAY_LINK, TRANSFER, MANUAL. */
    public static final String PAYMENT_METHOD = "paymentMethod";
    /** The desk's reference of the payment (the terminal's authorization…). */
    public static final String PAYMENT_REFERENCE = "paymentReference";
    public static final String PAYMENT_OUTCOME = "paymentOutcome";
    public static final String REFUND_OUTCOME = "refundOutcome";
    public static final String REVERSAL_OUTCOME = "reversalOutcome";
    /** The PMS's posting of the charge, and of its reversal (their transaction numbers). */
    public static final String PMS_POSTING_ID = "pmsPostingId";
    public static final String PMS_REVERSAL_ID = "pmsReversalId";
    /** asignar-empresa: the company billed (a partner's code; blank: none) and its name; what the step did. */
    public static final String COMPANY_CODE = "companyCode";
    public static final String COMPANY_NAME = "companyName";
    public static final String COMPANY_OUTCOME = "companyOutcome";

    private ProcessVariables() {
    }
}
