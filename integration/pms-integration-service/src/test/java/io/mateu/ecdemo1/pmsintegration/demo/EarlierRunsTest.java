package io.mateu.ecdemo1.pmsintegration.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.integration.model.integration.IntegrationStatus;
import io.mateu.ecdemo1.integration.model.integration.IntegrationView;
import io.mateu.ecdemo1.integration.model.integration.OhipConnection;
import io.mateu.ecdemo1.pmsintegration.connections.Connections;
import io.mateu.ecdemo1.pmsintegration.ohip.OhipClient;
import io.mateu.ecdemo1.pmsintegration.ohip.PmsRejectedException;
import org.junit.jupiter.api.Test;

/** cancel-earlier-runs: what the demo wrote in Opera is cancelled, and nothing else. */
class EarlierRunsTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);
    static final ObjectMapper JSON = new ObjectMapper();
    static final OhipConnection CHAIN = new OhipConnection(null, "https://ohip", "key", "client", "secret", "ent");

    final OhipClient ohip = mock(OhipClient.class);
    final List<IntegrationView> integrations = new ArrayList<>();
    final Connections connections = new Connections() {
        @Override
        public Optional<OhipConnection> of(String pmsHotelCode) {
            return integrations.stream().anyMatch(i -> i.pmsHotelCode().equals(pmsHotelCode))
                    ? Optional.of(CHAIN) : Optional.empty();
        }

        @Override
        public List<IntegrationView> integrations() {
            return integrations;
        }

        @Override
        public Optional<OhipConnection> chain(String pmsHotelCode) {
            return Optional.of(CHAIN);
        }
    };

    static String reservation(String id, String context, String status) {
        return """
                {"reservationIdList": [{"id": "%s", "type": "Reservation"}, {"id": "9%s", "type": "Confirmation"}],
                 "externalReferences": [{"id": "9%s", "idContext": "OPERA"}, {"id": "LOC%s", "idContext": "%s"}],
                 "reservationStatus": "%s"}""".formatted(id, id, id, id, context, status);
    }

    /** Opera's answer: these reservations arriving in the month of 2026-11, nothing in any other. */
    void operaHas(String... reservations) throws Exception {
        var november = JSON.readTree("{\"reservations\": {\"hasMore\": false, \"reservationInfo\": ["
                + String.join(",", reservations) + "]}}");
        var empty = JSON.readTree("{\"reservations\": {\"hasMore\": false}}");
        when(ohip.getWith(any(OhipConnection.class), anyString(), anyString(), any(Object[].class))).thenAnswer(call -> {
            var month = (LocalDate) call.getArgument(6);
            return new OhipClient.Response(month.equals(LocalDate.of(2026, 11, 1)) ? november : empty, null);
        });
    }

    @Test
    void ourReservationsAreCancelled_theChainsAndTheInHouseOnesAreNot() throws Exception {
        integrations.add(new IntegrationView("1", "MRU01", "XMAR", IntegrationStatus.ACTIVE));
        operaHas(reservation("101", "ECDEMO1-10090741", "Reserved"),
                reservation("102", "ECLOCAL-10181353", "Reserved"),
                reservation("103", "ECDEMO1", "InHouse"),
                reservation("104", "IMPORTCN", "Reserved"),
                reservation("105", "ECDEMO1-09280410", "Cancelled"));

        var progress = new ArrayList<String>();
        var outcome = new EarlierRuns(ohip, connections, List.of(), "OTROS", CLOCK).cancel(progress::add);

        assertThat(outcome.cancelled()).isEqualTo(2);
        assertThat(outcome.inHouse()).isEqualTo(1);
        assertThat(outcome.summary()).contains("103", "check it out by hand");
        verify(ohip).postWith(eq(CHAIN), eq("XMAR"), anyString(), any(), eq("XMAR"), eq("101"));
        verify(ohip).postWith(eq(CHAIN), eq("XMAR"), anyString(), any(), eq("XMAR"), eq("102"));
        verify(ohip, never()).postWith(any(), anyString(), anyString(), any(), anyString(), eq("103"));
        verify(ohip, never()).postWith(any(), anyString(), anyString(), any(), anyString(), eq("104"));
        assertThat(progress).anyMatch(p -> p.contains("XMAR: 4 reservations of earlier runs, 2 to cancel"));
    }

    @Test
    void aConfiguredPropertyIsCleanedWithTheChainsConnectionWhenNoIntegrationNamesIt() throws Exception {
        operaHas(reservation("201", "ECDEMO1-10070004", "Reserved"));

        var outcome = new EarlierRuns(ohip, connections, List.of("XMAR"), "OTROS", CLOCK).cancel(p -> { });

        assertThat(outcome.cancelled()).isEqualTo(1);
        verify(ohip).postWith(eq(CHAIN), eq("XMAR"), anyString(), any(), eq("XMAR"), eq("201"));
    }

    @Test
    void oneOperaRefusesIsReported_andTheRestAreCancelled() throws Exception {
        operaHas(reservation("301", "ECDEMO1", "Reserved"), reservation("302", "ECDEMO1", "Reserved"));
        when(ohip.postWith(any(), anyString(), anyString(), any(), anyString(), eq("301")))
                .thenThrow(new PmsRejectedException(400, "RSV00001", "cannot be cancelled"));

        var outcome = new EarlierRuns(ohip, connections, List.of("XMAR"), "OTROS", CLOCK).cancel(p -> { });

        assertThat(outcome.cancelled()).isEqualTo(1);
        assertThat(outcome.refused()).isEqualTo(1);
        assertThat(outcome.summary()).contains("301", "refused");
    }
}
