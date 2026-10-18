package io.mateu.ecdemo1.pmsintegration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * What the connector writes to Opera the same way for every property. How to reach each property
 * — gateway, application key, client, secret, enterprise — is not here: it is each hotel's
 * integration's (see {@code Connections}).
 *
 * @param externalSystemCode the context the CRS's locator goes under as a reservation's external
 *                           reference: it qualifies every external reference this adapter writes and
 *                           searches by. The demo's is {@code ECDEMO1}, or {@code ECDEMO1-<MMddHHmm>} — a new
 *                           one each time ec1 is put back to zero (deploy/demo/zero.sh, reset-demo): an
 *                           earlier run's reservations are cancelled, not deleted, and a new locator
 *                           must not find one
 * @param versionUdf         the numeric UDF of the reservation that carries the CRS version written
 *                           — the order guard (HLA R18, R28)
 * @param payAtHotelMethod   the payment method a reservation with no payment is recorded with.
 *                           Opera requires one; the CRS has none for "pays at the desk"
 * @param crmExternalSystem  the context of the guest profile's reference to its customer in the MDM —
 *                           the AF's «External Reference ID (Type: CRM)», the CRM_GUID. Blank: not stamped
 * @param profileReferences  whether guest profiles carry external references at all. A tenant takes
 *                           them only for external systems configured as interfaces of the property
 *                           (else 400 OPERAWS-GEN01187); without them, a reservation's guest profile is
 *                           found through the reservation, by the CRS locator it does carry
 * @param postDeposits       whether the payments the central office collected are posted to the
 *                           reservation's folio. Posting needs a cashier, and OPERA takes it from the
 *                           integration user's configuration (else 400 FOF00094 «Invalid Cashier»);
 *                           off, they stay in the CRS and the reservation is written without them
 * @param knownProperties    the chain's properties, named here rather than listed by the tenant: a
 *                           client without access to the hub gets 403 asking for the list. Each is read
 *                           on its own — which a client of the property may do — for its name
 * @param customReference    what every reservation this adapter writes carries in Opera's «Custom
 *                           Reference»: a search filter in Opera's reservation list, so the desk can
 *                           find the integration's reservations. Blank: not written
 * @param cashierId          the cashier the integration posts and checks out with. OPERA takes the
 *                           integration user's own cashier when none is sent, and the tenant's user has
 *                           none (400 FOF00094 «Invalid Cashier»): XMAR's is 69721441, «EC-DEMO1
 *                           Integración» (an InterfaceCashier). Blank: none is sent
 */
@ConfigurationProperties("ohip")
public record OhipProperties(String externalSystemCode, String versionUdf, String payAtHotelMethod, Duration timeout,
                             String crmExternalSystem, Boolean profileReferences, Boolean postDeposits,
                             java.util.List<String> knownProperties, String customReference, String cashierId) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public OhipProperties {
        if (externalSystemCode == null) externalSystemCode = "ECDEMO1";
        if (versionUdf == null) versionUdf = "CRS_VERSION";
        if (payAtHotelMethod == null) payAtHotelMethod = "CA";
        if (timeout == null) timeout = Duration.ofSeconds(30);
        if (crmExternalSystem == null) crmExternalSystem = "CRM";
        if (profileReferences == null) profileReferences = true;
        if (postDeposits == null) postDeposits = true;
        if (knownProperties == null) knownProperties = java.util.List.of();
        if (customReference == null) customReference = "EC-DEMO1";
        if (cashierId != null && cashierId.isBlank()) cashierId = null;
    }

    /** As before the cashier: none. */
    public OhipProperties(String externalSystemCode, String versionUdf, String payAtHotelMethod, Duration timeout,
                          String crmExternalSystem, Boolean profileReferences, Boolean postDeposits,
                          java.util.List<String> knownProperties, String customReference) {
        this(externalSystemCode, versionUdf, payAtHotelMethod, timeout, crmExternalSystem, profileReferences, postDeposits,
                knownProperties, customReference, null);
    }
}
