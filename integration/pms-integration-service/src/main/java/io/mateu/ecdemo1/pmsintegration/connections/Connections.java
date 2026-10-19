package io.mateu.ecdemo1.pmsintegration.connections;

import io.mateu.ecdemo1.integration.model.integration.IntegrationView;
import io.mateu.ecdemo1.integration.model.integration.OhipConnection;

import java.util.List;
import java.util.Optional;

/**
 * Where the connector learns how to reach each Opera property. Each hotel's integration carries its
 * own connection (HLA, «Aislamiento por hotel»): nothing about a tenant is configuration here.
 */
public interface Connections {

    /** How to reach this Opera property, or empty if no integration names it. */
    Optional<OhipConnection> of(String pmsHotelCode);

    /** Every hotel's integration, whatever its state. */
    List<IntegrationView> integrations();

    /**
     * The chain's own connection — what a new integration starts from — for a property no integration
     * names: only the demo's reset uses it, to clean what earlier runs wrote. Empty where it is not known.
     */
    default Optional<OhipConnection> chain(String pmsHotelCode) {
        return Optional.empty();
    }
}
