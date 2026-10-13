package io.mateu.ecdemo1.pmsintegration.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mateu.ecdemo1.integration.model.integration.IntegrationView;
import io.mateu.ecdemo1.integration.model.integration.OhipConnection;
import io.mateu.ecdemo1.pmsintegration.clients.IntegrationClients;
import io.mateu.ecdemo1.pmsintegration.config.OhipProperties;
import io.mateu.ecdemo1.pmsintegration.config.TolerantReader;
import io.mateu.ecdemo1.pmsintegration.connections.Connections;
import io.mateu.ecdemo1.pmsintegration.ohip.OhipClient;
import io.mateu.ecdemo1.pmsintegration.ohip.OperaFrontDesk;
import io.mateu.ecdemo1.pmsintegration.ohip.OperaReservations;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskTracing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The company of a stay onto Opera («asignar-empresa») — against a fake of OHIP that keeps the
 * reservation's Company profile as XMAR does: the profile attached by the reservation's PUT, the stay
 * routed to it by the cashiering API, and both taken away; nothing written when Opera has it already,
 * and a company not in Opera yet waits for its projection.
 */
class CompanyHandlersTest {

    record Call(String method, String path, String body) {
    }

    final List<Call> calls = new ArrayList<>();
    String company;
    HttpServer ohip;

    final IntegrationClients integration = mock(IntegrationClients.class);
    final PmsTasksTest.RecordingSink sink = new PmsTasksTest.RecordingSink();
    final ObjectMapper mapper = new ObjectMapper();
    TaskDispatcher dispatcher;

    @BeforeEach
    void start() throws IOException {
        ohip = HttpServer.create(new InetSocketAddress(0), 0);
        ohip.createContext("/oauth/v1/tokens", exchange -> reply(exchange, 200,
                "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        ohip.createContext("/", exchange -> {
            var method = exchange.getRequestMethod();
            var uri = exchange.getRequestURI().toString();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(method, uri, body));
            if (method.equals("GET") && uri.startsWith("/rsv/v1/hotels/XMAR/reservations/39486034")) {
                var profiles = company == null ? "" : """
                        , "reservationProfiles": {"reservationProfile": [{"reservationProfileType": "Company",
                            "profileIdList": [{"id": "%s", "type": "Profile"}]}]}""".formatted(company);
                reply(exchange, 200, """
                        {"reservations": {"reservation": [{
                          "reservationIdList": [{"id": "39486034", "type": "Reservation"}],
                          "roomStay": {"arrivalDate": "2026-11-03", "departureDate": "2026-11-05"},
                          "reservationStatus": "Reserved"%s}]}}
                        """.formatted(profiles));
            } else if (method.equals("PUT") && uri.equals("/rsv/v1/hotels/XMAR/reservations/39486034")) {
                var entry = mapper.readTree(body).path("reservations").path(0).path("reservationProfiles")
                        .path("reservationProfile").path(0);
                company = entry.path("profileIdList").path(0).path("id").asText(null);
                reply(exchange, 200, "{}");
            } else if (uri.startsWith("/csh/v1/hotels/XMAR/reservations/39486034/routingInstructions")) {
                reply(exchange, 200, "{}");
            } else {
                reply(exchange, 404, "{\"title\":\"Not found\"}");
            }
        });
        ohip.start();
        var connection = new OhipConnection("XMAR", "http://localhost:" + ohip.getAddress().getPort(), "app", "id", "secret", "RIUE");
        var properties = new OhipProperties("ECDEMO1-09280410", null, null, Duration.ofSeconds(2), null, null, false, null,
                null, "69721441");
        var client = new OhipClient(new Connections() {
            @Override
            public Optional<OhipConnection> of(String pmsHotelCode) {
                return Optional.of(connection);
            }

            @Override
            public List<IntegrationView> integrations() {
                return List.of();
            }
        }, properties, new TolerantReader(new ObjectMapper()), Clock.systemUTC());
        var handlers = new CompanyHandlers(new OperaFrontDesk(client, properties, mapper),
                new OperaReservations(client, properties, mapper), integration, List.of("1100", "1137"));
        dispatcher = new TaskDispatcher(new TaskRegistry(List.of(new PmsTasks().assignCompanyTask(handlers,
                mock(RetryWatch.class)))), sink, Cancellations.NONE, false, TaskTracing.NOOP);
    }

    @AfterEach
    void stop() {
        ohip.stop(0);
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    void run(String companyCode) {
        var variables = new ArrayList<>(List.of(
                new Variable("definitionId", "asignar-empresa"), new Variable("processKey", "asignar-empresa:MRU01/GSX4AK"),
                new Variable("hotelCode", "MRU01"), new Variable("locator", "GSX4AK"), new Variable("pmsHotelCode", "XMAR"),
                new Variable("pmsReservationId", "39486034"), new Variable("stayId", "GSX4AK"), new Variable("eventId", "E-1")));
        if (companyCode != null) {
            variables.add(new Variable("companyCode", companyCode));
        }
        dispatcher.dispatch(new TaskExecutionRequested("TE-1", "PROC-1", "asignar-empresa", "assign-company",
                "assign-company@1", variables));
    }

    List<Call> writes() {
        return calls.stream().filter(c -> !c.method().equals("GET")).toList();
    }

    @Test
    void theCompanyIsAttachedToTheReservationAndTheStayRoutedToIt() throws Exception {
        when(integration.partnerProfile("ACME")).thenReturn(Optional.of(new IntegrationClients.PartnerProfile("ACME",
                "Company", "77001", 1)));

        run("ACME");

        assertThat(sink.replies).containsExactly("COMPLETED [companyOutcome=DONE, pmsReservationId=39486034]");
        assertThat(company).isEqualTo("77001");
        assertThat(writes()).extracting(Call::method, Call::path).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PUT", "/rsv/v1/hotels/XMAR/reservations/39486034"),
                org.assertj.core.groups.Tuple.tuple("PUT", "/csh/v1/hotels/XMAR/reservations/39486034/routingInstructions/create"));
        var folio = mapper.readTree(writes().get(1).body()).path("criteria").path("folio");
        assertThat(folio.path("folioWindowNo").asInt()).isEqualTo(2);
        assertThat(folio.path("payeeInfo").path("payeeId").path("id").asText()).isEqualTo("77001");
        assertThat(folio.path("instructions").path(0).path("duration").path("timeSpan").path("startDate").asText())
                .isEqualTo("2026-11-03");
        assertThat(folio.path("instructions").path(0).path("transactionCodes").findValuesAsText("transactionCode"))
                .containsExactly("1100", "1137");
    }

    @Test
    void aCompanyTheReservationHasAlreadyIsNotWrittenAgain() {
        company = "77001";
        when(integration.partnerProfile("ACME")).thenReturn(Optional.of(new IntegrationClients.PartnerProfile("ACME",
                "Company", "77001", 1)));

        run("ACME");

        assertThat(sink.replies).containsExactly("COMPLETED [companyOutcome=STALE, pmsReservationId=39486034]");
        assertThat(writes()).isEmpty();
    }

    @Test
    void noCompanyTakesItsProfileAndItsRoutingAway() {
        company = "77001";

        run(null);

        assertThat(sink.replies).containsExactly("COMPLETED [companyOutcome=DONE, pmsReservationId=39486034]");
        assertThat(company).isNull();
        assertThat(writes()).extracting(Call::method).containsExactly("DELETE", "PUT");
        assertThat(writes().getFirst().path()).contains("folioWindowNo=2").contains("payeeId=77001");
    }

    @Test
    void aCompanyNotInOperaYetWaitsForItsProjection() {
        when(integration.partnerProfile("ACME")).thenReturn(Optional.empty());

        run("ACME");

        assertThat(sink.replies).containsExactly("COMPLETED [companyOutcome=WAIT, pmsReservationId=39486034]");
        assertThat(writes()).isEmpty();
        verify(integration).await(eq("asignar-empresa:MRU01/GSX4AK"), eq("asignar-empresa"), eq("MRU01"), eq("GSX4AK"),
                anyList(), argThat(causes -> causes.size() == 1 && causes.getFirst().key().startsWith("MISSING_PARTNER")));
    }
}
