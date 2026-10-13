package io.mateu.ecdemo1.integration.model.process;

/** The ids of the integration's process definitions, as they are in ec-definitions. */
public final class Definitions {

    public static final String PROJECT_RESERVATION = "proyectar-reserva";
    public static final String PROJECT_CANCELLATION = "proyectar-cancelacion";
    public static final String PROJECT_PARTNER = "proyectar-interlocutor";
    /** The hotel says a guest did not arrive: the CRS cancels the booking as a no-show, with its fee (HLA F006, #5). */
    public static final String REGISTER_NO_SHOW = "registrar-no-show";

    /** The onboarding of a hotel's integration: gates from registration to activation (F010). */
    public static final String ONBOARD_INTEGRATION = "alta-integracion";

    /**
     * The onboarding of a hotel's pms-fo integration — its front office fed from the PMS: gates from
     * registration to activation, as the crs-pms one but smaller (connection, catalogue, backfill,
     * activation).
     */
    public static final String ONBOARD_FO_INTEGRATION = "alta-integracion-fo";
    /** A PMS reservation, as the PMS holds it, into the hotel's front office as a stay. */
    public static final String PROJECT_STAY = "proyectar-estancia";

    /**
     * The reception, up to the PMS — the master of the stay (pms-fo): the desk checked a guest in,
     * checked one out, or nobody came. Each records it in the PMS; the no-show is then reported on to
     * the CRS, the master of the sale, which applies its fee («registrar-no-show»).
     */
    public static final String REGISTER_CHECK_IN = "registrar-checkin";
    public static final String REGISTER_CHECK_OUT = "registrar-checkout";
    public static final String REGISTER_NO_SHOW_PMS = "registrar-no-show-pms";

    /**
     * The desk's charges, onto the PMS's folio (pms-fo): a charge of the front office's folio (a late
     * check-out, an extra, a consumption) is posted to the reservation's folio in the PMS; its void is
     * reversed there. One process per folio line and operation.
     */
    public static final String REGISTER_CHARGE = "registrar-cargo";
    public static final String REVERSE_CHARGE = "anular-cargo";

    /**
     * The desk's payments, onto the PMS's folio (pms-fo): a payment or advance captured at the till is
     * posted to the reservation's folio in the PMS; its refund is posted back there. One process per
     * payment and operation.
     */
    public static final String REGISTER_PAYMENT = "registrar-cobro";
    public static final String REFUND_PAYMENT = "devolver-cobro";

    /**
     * The company of a stay — who is billed when neither the agency nor the guest is —, onto the PMS
     * (pms-fo): its profile attached to the reservation and the accommodation routed to it, or detached.
     * One process per stay: the last change the desk made wins.
     */
    public static final String ASSIGN_COMPANY = "asignar-empresa";

    /** The message a waiting process is resumed with once its last cause is resolved. */
    public static final String CAUSES_RESOLVED_MESSAGE = "causes-resolved";

    /**
     * The gates of the onboarding, each a message of its own so that a message resent for one gate
     * can never open the next. Correlated by the integration's process key.
     */
    public static final String GATE_CONNECTIVITY = "integration-connectivity-ok";
    public static final String GATE_CONFIGURED = "integration-property-configured";
    public static final String GATE_MAPPING = "integration-mapping-approved";
    public static final String GATE_PARTNERS = "integration-partners-synced";
    public static final String GATE_BACKFILL_CLEAR = "integration-backfill-clear";
    public static final String GATE_WINDOW = "integration-window-covered";
    public static final String GATE_ACTIVATION = "integration-activation-requested";

    /** The gates of the pms-fo onboarding, each its own message as the crs-pms ones. */
    public static final String GATE_FO_CONNECTIVITY = "fo-integration-connectivity-ok";
    public static final String GATE_FO_CATALOGUE = "fo-integration-catalogue-synced";
    public static final String GATE_FO_BACKFILL = "fo-integration-backfill-done";
    public static final String GATE_FO_ACTIVATION = "fo-integration-activation-requested";

    private Definitions() {
    }
}
