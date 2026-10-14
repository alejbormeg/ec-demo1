package io.mateu.ecdemo1.integrations.frontoffice;

import com.sun.net.httpserver.HttpServer;
import io.mateu.ecdemo1.integration.model.pms.PmsReservationChanged;
import io.mateu.ecdemo1.integrations.store.FoBackfillRun;
import io.mateu.ecdemo1.integrations.store.FoBackfillRunRepository;
import io.mateu.ecdemo1.integrations.store.FoIntegrationStatus;
import io.mateu.ecdemo1.integrations.store.FrontOfficeIntegration;
import io.mateu.ecdemo1.integrations.store.FrontOfficeIntegrationRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A front office fed from its PMS: the pms-fo integration's onboarding gate by gate, its backfill, its
 * polling of the PMS and the connector's events — against a real Postgres (and the broker the app binds to), with the
 * connector and the front office answered by one small double. The steps are run as the engine would
 * run them; the gates are looked at as {@link FoGates} would.
 */
@SpringBootTest(properties = {
        "integrations.crypto-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        // Scheduled work off: the test looks at gates, ticks the backfill and polls itself.
        "integrations.gate-check=1h", "integrations.backfill-tick=1h", "integrations.front-office.poll=1h",
        "integrations.opera.gateway-url=https://ohip.example", "integrations.opera.app-key=chain-app",
        "integrations.opera.client-id=chain-client", "integrations.opera.client-secret=ch41n",
        "integrations.opera.enterprise-id=RIUE",
        "integrations.backfill-per-tick=2", "integrations.front-office.horizon-days=30"})
@AutoConfigureMockMvc
@Testcontainers
class FrontOfficeIntegrationsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.1.7")
            // size=8g: unsized, a tmpfs gets half the Docker VM's memory, under Redpanda's 5 GiB free-space
            // floor, and every write is refused. It is a ceiling, not an allocation.
            .withTmpFs(Map.of("/var/lib/redpanda/data", "rw,size=8g"));

    static HttpServer others;
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final LocalDate TODAY = LocalDate.now();

    // The world around the integration, changed by each test.
    static volatile boolean operaWorks = true;
    static volatile boolean frontOfficeUp = true;
    /** The hotels the front office says it serves; null: it does not say (404), as one built before it did. */
    static volatile String frontOfficeServes;
    /** The catalogue command the front office says it holds. */
    static volatile String frontOfficeHolds = null;
    /** The property's reservations: id → last modification. */
    static final Map<String, String> reservations = new java.util.concurrent.ConcurrentHashMap<>();

    static String stamps(String query) {
        var since = query != null && query.contains("modifiedSince=")
                ? java.net.URLDecoder.decode(query.replaceAll(".*modifiedSince=([^&]*).*", "$1"), StandardCharsets.UTF_8) : null;
        var list = new ArrayList<String>();
        reservations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (since == null || e.getValue().compareTo(since) >= 0) {
                var n = Integer.parseInt(e.getKey().substring(1));
                list.add("""
                        {"pmsReservationId":"%s","confirmationNumber":"C%s","arrival":"%s","departure":"%s","status":"Reserved","lastModified":"%s"}"""
                        .formatted(e.getKey(), n, TODAY.plusDays(n), TODAY.plusDays(n + 2), e.getValue()));
            }
        });
        return "[" + String.join(",", list) + "]";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        others = HttpServer.create(new InetSocketAddress(0), 0);
        others.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getRawQuery();
            exchange.getRequestBody().readAllBytes();
            calls.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
            String body = switch (path) {
                case "/connections/verify" -> operaWorks ? "{\"ok\":true,\"message\":\"Token granted\"}"
                        : "{\"ok\":false,\"message\":\"OHIP 401: invalid client\"}";
                case "/connections/properties" -> "[{\"code\":\"XMAR\",\"name\":\"Riu Demo Mar\"}]";
                case "/front-office/catalogue" -> """
                        [{"type":"ROOM_TYPE","code":"STDK","description":"Estándar King"},
                         {"type":"ROOM_TYPE","code":"SJSB","description":"Suite Junior Standard Balcón"},
                         {"type":"PACKAGE","code":"BRKFST","description":"Desayuno buffet"},
                         {"type":"ROOM","code":"001","description":"Doble Baño Jardín Balcón","extra":"DBJB"}]""";
                case "/front-office/reservations" -> stamps(query);
                case "/api/hotels" -> !frontOfficeUp ? null : frontOfficeServes == null ? "404" : frontOfficeServes;
                case "/api/pms-catalogue/summary" -> frontOfficeUp ? """
                        {"pmsHotelCode":%s,"commandId":%s,"syncedAt":null,"counts":{"ROOM_TYPE":2,"PACKAGE":1,"ROOM":1}}"""
                        .formatted(frontOfficeHolds == null ? "null" : "\"XMAR\"",
                                frontOfficeHolds == null ? "null" : "\"" + frontOfficeHolds + "\"") : null;
                default -> "{}";
            };
            if (body == null) {
                exchange.sendResponseHeaders(503, -1);
            } else if (body.equals("404")) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        others.start();
        var url = "http://localhost:" + others.getAddress().getPort();
        registry.add("CRS_INTEGRATION_URL", () -> url);
        registry.add("PMS_INTEGRATION_URL", () -> url);
        registry.add("MAPPING_URL", () -> url);
        registry.add("ERP_URL", () -> url);
        registry.add("FRONT_OFFICE_URL", () -> url);
        registry.add("KAFKA_BROKERS", redpanda::getBootstrapServers);
    }

    @AfterAll
    static void stop() {
        others.stop(0);
    }

    @Autowired
    FrontOfficeIntegrations lifecycle;
    @Autowired
    FoGates gates;
    @Autowired
    FoBackfill backfill;
    @Autowired
    FoPolling polling;
    @Autowired
    PmsReservationEvents events;
    @Autowired
    ReceptionEvents reception;
    @Autowired
    io.mateu.ecdemo1.integrations.config.StreamFunctions functions;
    @Autowired
    FrontOfficeIntegrationRepository integrations;
    @Autowired
    FoBackfillRunRepository runs;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        runs.deleteAll();
        integrations.deleteAll();
        jdbc.update("delete from outbox_message");
        calls.clear();
        operaWorks = true;
        frontOfficeUp = true;
        frontOfficeHolds = null;
        frontOfficeServes = SERVES_MRU01;
        reservations.clear();
        for (var n = 1; n <= 5; n++) {
            reservations.put("R" + n, "2026-09-2%dT10:00:00".formatted(n));
        }
    }

    static final String SERVES_MRU01 = "[{\"code\":\"MRU01\",\"name\":\"Riu Demo Mauricio\",\"pmsHotelCode\":\"XMAR\"}]";

    /** Registered without saying a scope: it gets the default, only what the chain's integration wrote. */
    String register() {
        var i = lifecycle.register(new FrontOfficeIntegrations.Registration("XMAR", "MRU01", "Riu Demo Mar", null,
                null, null), "ana");
        assertThat(i.scope).isEqualTo(FrontOfficeIntegration.Scope.CHAIN);
        return i.id;
    }

    FrontOfficeIntegration integration(String id) {
        return integrations.findById(id).orElseThrow();
    }

    @Test
    void anOnboardingGoesThroughItsGatesAndThenFollowsThePms() {
        var id = register();
        assertThat(integration(id).horizonDays).isEqualTo(30);
        assertThat(integration(id).frontOfficeUrl).startsWith("http://localhost:");
        assertThat(outbox("outboxUpstream")).anyMatch(p -> p.contains("\"workflowDefinitionId\":\"alta-integracion-fo\"")
                && p.contains("alta-integracion-fo:" + id) && p.contains("\"pmsHotelCode\""));

        // Connectivity: the front office down fails it, and says so; once it answers, the gate opens.
        frontOfficeUp = false;
        lifecycle.stepVerifyConnectivity(id);
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.CONNECTIVITY_FAILED);
        assertThat(integration(id).connectivityMessage).contains("Opera: Token granted").contains("unreachable");
        assertThat(outbox("notifications")).anyMatch(p -> p.contains("INTEGRATION_NEEDS_ATTENTION")
                && p.contains("integration/fo-XMAR") && p.contains("/integrations/frontoffice/XMAR"));
        assertThat(lifecycle.gateOpen(integration(id))).isFalse();
        frontOfficeUp = true;
        lifecycle.recheck(id, "test");
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.CREATED);
        assertGateSignalled(id, "fo-integration-connectivity-ok");

        // The catalogue: sent to the front office; the gate opens only once it holds that very command.
        lifecycle.stepSyncCatalogue(id);
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.SYNCING_CATALOGUE);
        var commandId = integration(id).catalogueCommandId;
        assertThat(outbox("frontOfficeCommands")).singleElement().satisfies(p -> assertThat(p)
                .contains("\"type\":\"replace-catalogue\"").contains(commandId).contains("Suite Junior Standard Balcón"));
        frontOfficeHolds = "an-older-one";
        lifecycle.recheck(id, "test");
        assertThat(lifecycle.gateOpen(integration(id))).isFalse();
        frontOfficeHolds = commandId;
        lifecycle.recheck(id, "test");
        assertThat(integration(id).catalogueSummary).isEqualTo("XMAR: 2 room types, 0 rate plans, 1 packages, 1 rooms");
        assertGateSignalled(id, "fo-integration-catalogue-synced");

        // The backfill: the window read once, two a tick, one «proyectar-estancia» per reservation.
        lifecycle.stepStartBackfill(id);
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.BACKFILLING);
        assertThat(calls).anyMatch(c -> c.startsWith("GET /front-office/reservations?hotelId=XMAR&from=" + TODAY
                + "&to=" + TODAY.plusDays(30) + "&scope=CHAIN") && !c.contains("modifiedSince"));
        backfill.tick();
        assertThat(stayKeys()).hasSize(2);
        assertThat(lifecycle.gateOpen(integration(id))).isFalse();
        backfill.tick();
        backfill.tick();
        assertThat(stayKeys()).containsExactly(
                "proyectar-estancia:XMAR:R1:2026-09-21T10:00:00", "proyectar-estancia:XMAR:R2:2026-09-22T10:00:00",
                "proyectar-estancia:XMAR:R3:2026-09-23T10:00:00", "proyectar-estancia:XMAR:R4:2026-09-24T10:00:00",
                "proyectar-estancia:XMAR:R5:2026-09-25T10:00:00");
        assertThat(outbox("outboxUpstream")).filteredOn(p -> p.contains("proyectar-estancia:"))
                .allMatch(p -> p.contains("fo-backfill:") && p.contains("\"pmsReservationId\""));
        assertThat(runs.findAll()).singleElement().satisfies(r -> {
            assertThat(r.status).isEqualTo(FoBackfillRun.Status.COMPLETED);
            assertThat(r.dispatched).isEqualTo(5);
            assertThat(r.cursorAtStart).isEqualTo("2026-09-25T10:00:00");
        });
        assertGateSignalled(id, "fo-integration-backfill-done");

        // Ready; a person activates it.
        lifecycle.stepAwaitActivation(id);
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.READY_TO_ACTIVATE);
        assertThat(outbox("notifications")).anyMatch(p -> p.contains("ready to activate"));
        assertThat(lifecycle.gateOpen(integration(id))).isFalse();
        lifecycle.activate(id, "ana");
        assertGateSignalled(id, "fo-integration-activation-requested");
        lifecycle.stepActivate(id);
        assertThat(integration(id)).satisfies(i -> {
            assertThat(i.getStatus()).isEqualTo(FoIntegrationStatus.ACTIVE);
            assertThat(i.gate).isNull();
            assertThat(i.pollCursor).isEqualTo("2026-09-25T10:00:00");
        });

        // A change made in Opera itself: the poll asks from the cursor, projects that version, moves on.
        reservations.put("R2", "2026-09-27T21:57:59");
        calls.clear();
        polling.poll(id);
        assertThat(calls).anyMatch(c -> c.contains("/front-office/reservations") && c.contains("modifiedSince=2026-09-25T10%3A00%3A00")
                || c.contains("modifiedSince=2026-09-25T10:00:00"));
        assertThat(stayKeys()).contains("proyectar-estancia:XMAR:R2:2026-09-27T21:57:59").hasSize(6);
        assertThat(integration(id)).satisfies(i -> {
            assertThat(i.pollCursor).isEqualTo("2026-09-27T21:57:59");
            assertThat(i.lastPollChanges).isEqualTo(1);
            assertThat(i.lastPollAt).isNotNull();
        });

        // Nothing changed since: nothing new is projected — the cursor's own comes back, and the engine
        // already has its process — and the cursor stays.
        calls.clear();
        polling.poll(id);
        assertThat(calls).anyMatch(c -> c.contains("modifiedSince=2026-09-27T21%3A57%3A59") || c.contains("modifiedSince=2026-09-27T21:57:59"));
        assertThat(stayKeys()).hasSize(6);
        assertThat(integration(id).pollCursor).isEqualTo("2026-09-27T21:57:59");
        assertThat(integration(id).lastPollChanges).isZero();

        // The connector wrote one into Opera: announced by its event, projected at once.
        assertThat(events.on(new PmsReservationChanged("e-1", "XMAR", "R9", "MRU01", "ABC123", "proyectar-reserva",
                Instant.now()))).isTrue();
        assertThat(stayKeys()).contains("proyectar-estancia:XMAR:R9:evt-e-1");
        assertThat(outbox("outboxUpstream")).anyMatch(p -> p.contains("proyectar-estancia:XMAR:R9:evt-e-1")
                && p.contains("pms-write:proyectar-reserva"));
        // Another property feeds no front office.
        assertThat(events.on(new PmsReservationChanged("e-2", "XMU", "R1", "MRU02", "X", "proyectar-reserva", Instant.now())))
                .isFalse();

        // Paused, the events wait for the poll after resuming.
        lifecycle.pause(id, "ana");
        assertThat(events.on(new PmsReservationChanged("e-3", "XMAR", "R8", "MRU01", "Y", "proyectar-reserva", Instant.now())))
                .isFalse();
        assertThatThrownBy(() -> polling.pollNow(id, "ana")).isInstanceOf(IllegalStateException.class);
        lifecycle.resume(id, "ana");
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.ACTIVE);
    }

    @Test
    void theConnectorsEventIsReadTolerantlyFromItsTopic() {
        var id = activeIntegration();
        // As the binding hands it over: bytes, with a field this service does not know yet.
        functions.consumePmsReservations().accept(org.springframework.messaging.support.MessageBuilder.withPayload("""
                {"eventId":"k-1","pmsHotelCode":"XMAR","pmsReservationId":"R7","crsHotelCode":"MRU01","locator":"L7",
                 "origin":"proyectar-cancelacion","occurredAt":"2026-09-27T20:00:00Z","somethingNew":1}"""
                .getBytes(StandardCharsets.UTF_8)).build());
        assertThat(stayKeys()).contains("proyectar-estancia:XMAR:R7:evt-k-1");
        assertThat(outbox("outboxUpstream")).anyMatch(p -> p.contains("pms-write:proyectar-cancelacion"));
        // One it cannot read is skipped, not retried for ever.
        functions.consumePmsReservations().accept(org.springframework.messaging.support.MessageBuilder
                .withPayload("not json".getBytes(StandardCharsets.UTF_8)).build());
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.ACTIVE);
    }

    @Test
    void theFrontOfficeHasToServeTheHotelAndTakeTheProperty() {
        assertThatThrownBy(() -> lifecycle.register(new FrontOfficeIntegrations.Registration("XMAR", "RANDOM1", null, null,
                null, null), "ana")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not serve RANDOM1").hasMessageContaining("MRU01 (Riu Demo Mauricio, Opera XMAR)");
        assertThatThrownBy(() -> lifecycle.register(new FrontOfficeIntegrations.Registration("XMU", "MRU01", null, null,
                null, null), "ana")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes the stays of Opera XMAR, not XMU");
        assertThat(integrations.findAll()).isEmpty();
        assertThat(calls).anyMatch(c -> c.equals("GET /api/hotels"));

        // A front office that does not say which hotels it serves is not a refusal.
        frontOfficeServes = null;
        assertThat(lifecycle.register(new FrontOfficeIntegrations.Registration("XMAR", "MRU01", null, null, null, null),
                "ana").frontOfficeCode).isEqualTo("MRU01");
    }

    @Test
    void registeredWhileTheFrontOfficeWasDownItsConnectivityFailsIfItDoesNotServeTheHotel() {
        frontOfficeUp = false;
        var id = lifecycle.register(new FrontOfficeIntegrations.Registration("XMAR", "RANDOM1", null, null, null, null),
                "ana").id;
        frontOfficeUp = true;
        lifecycle.stepVerifyConnectivity(id);
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.CONNECTIVITY_FAILED);
        assertThat(integration(id).connectivityMessage).contains("does not serve RANDOM1");
        assertThat(lifecycle.gateOpen(integration(id))).isFalse();
    }

    @Test
    void aPropertyOnlyAFrontOfficeReadsIsReachedWithTheChainsConnection() throws Exception {
        register();
        mvc.perform(get("/integrations/connections/XMAR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pmsHotelCode").value("XMAR"))
                .andExpect(jsonPath("$.gatewayUrl").value("https://ohip.example"))
                .andExpect(jsonPath("$.clientSecret").value("ch41n"));
        mvc.perform(get("/integrations/connections/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/integrations/front-office/XMAR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.frontOfficeCode").value("MRU01"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void aPropertyFeedsOneFrontOfficeAndADecommissionedOneStopsItsOnboarding() {
        var id = register();
        assertThatThrownBy(this::register).isInstanceOf(IllegalStateException.class).hasMessageContaining("already feeds");
        lifecycle.decommission(id, "ana");
        calls.clear();
        lifecycle.stepVerifyConnectivity(id);
        lifecycle.stepSyncCatalogue(id);
        assertThat(integration(id).gate).isNull();
        assertThat(calls).isEmpty();
        assertThat(outbox("frontOfficeCommands")).isEmpty();
        // Another one can be registered for it now.
        assertThat(register()).isNotEqualTo(id);
    }

    /** Onboarded straight to ACTIVE, as the engine would take it. */
    String activeIntegration() {
        var id = register();
        lifecycle.stepVerifyConnectivity(id);
        lifecycle.stepSyncCatalogue(id);
        lifecycle.stepStartBackfill(id);
        for (var n = 0; n < 3; n++) {
            backfill.tick();
        }
        lifecycle.stepAwaitActivation(id);
        lifecycle.activate(id, "ana");
        lifecycle.stepActivate(id);
        assertThat(integration(id).getStatus()).isEqualTo(FoIntegrationStatus.ACTIVE);
        return id;
    }

    @Test
    void theReceptionGoesUpToThePmsThroughTheEngine() {
        var at = Instant.parse("2026-09-29T10:00:00Z");
        var checkIn = new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.GuestCheckedIn("E-1", at, "MRU01",
                "GSX4AK", "GSX4AK", "XMAR", "39486034", "205", 2, "ana");
        // No active integration for the property: the PMS is not told (the desk's state stays in the front office).
        assertThat(reception.on(checkIn)).isNull();

        var id = activeIntegration();
        assertThat(reception.on(checkIn)).isEqualTo("registrar-checkin:MRU01/GSX4AK:205");
        var started = outbox("outboxUpstream").stream().filter(p -> p.contains("registrar-checkin:MRU01/GSX4AK:205")).toList();
        assertThat(started).singleElement().satisfies(p -> assertThat(p)
                .contains("\"workflowDefinitionId\":\"registrar-checkin\"")
                .contains("{\"name\":\"pmsReservationId\",\"value\":\"39486034\"}")
                .contains("{\"name\":\"roomNumber\",\"value\":\"205\"}")
                .contains("{\"name\":\"stayId\",\"value\":\"GSX4AK\"}")
                .contains("{\"name\":\"integrationId\",\"value\":\"" + id + "\"}"));
        // The same key twice: the engine starts it once. Another room, after Opera refused this one: another check-in.
        assertThat(reception.on(checkIn)).isEqualTo("registrar-checkin:MRU01/GSX4AK:205");
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.GuestCheckedIn("E-1b", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", "207", 2, "ana"))).isEqualTo("registrar-checkin:MRU01/GSX4AK:207");

        // Born in Opera: keyed by the property and Opera's id.
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.GuestCheckedOut("E-2", at,
                "MRU01", "OP-268338062", null, "XMAR", "39486099", "207", "ana"))).isEqualTo("registrar-checkout:XMAR/39486099");
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.NoShowReported("E-3", at,
                "MRU01", "KMNQ28", "KMNQ28", "XMAR", null, 2, "ana"))).isEqualTo("registrar-no-show-pms:MRU01/KMNQ28");
        // The desk's charges: one process per folio line — its charge, and its void.
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.ChargePosted("E-5", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", "L-1",
                io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.ChargeKind.LATE_CHECK_OUT, null,
                "Late check-out (salida 15:00)", new java.math.BigDecimal("50.00"), "MUR", "ana")))
                .isEqualTo("registrar-cargo:MRU01/GSX4AK:L-1");
        assertThat(outbox("outboxUpstream")).filteredOn(p -> p.contains("registrar-cargo:MRU01/GSX4AK:L-1"))
                .singleElement().satisfies(p -> assertThat(p)
                        .contains("{\"name\":\"lineId\",\"value\":\"L-1\"}")
                        .contains("{\"name\":\"chargeKind\",\"value\":\"LATE_CHECK_OUT\"}")
                        .contains("{\"name\":\"amount\",\"value\":\"50.00\"}")
                        .contains("{\"name\":\"currency\",\"value\":\"MUR\"}")
                        .contains("{\"name\":\"pmsReservationId\",\"value\":\"39486034\"}"));
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.ChargeVoided("E-6", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", "L-1",
                io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.ChargeKind.LATE_CHECK_OUT, null,
                "Late check-out (salida 15:00)", new java.math.BigDecimal("50.00"), "MUR", "ana")))
                .isEqualTo("anular-cargo:MRU01/GSX4AK:L-1");
        // The desk's payments: one process per payment — its posting, and its refund.
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.PaymentTaken("E-7", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", "P-1", "DEPOSIT", "CASH",
                new java.math.BigDecimal("100.00"), "MUR", null, "ana"))).isEqualTo("registrar-cobro:MRU01/GSX4AK:P-1");
        assertThat(outbox("outboxUpstream")).filteredOn(p -> p.contains("registrar-cobro:MRU01/GSX4AK:P-1"))
                .singleElement().satisfies(p -> assertThat(p)
                        .contains("{\"name\":\"paymentId\",\"value\":\"P-1\"}")
                        .contains("{\"name\":\"paymentMethod\",\"value\":\"CASH\"}")
                        .contains("{\"name\":\"paymentKind\",\"value\":\"DEPOSIT\"}")
                        .contains("{\"name\":\"amount\",\"value\":\"100.00\"}"));
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.PaymentRefunded("E-8", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", "P-1", "DEPOSIT", "CASH",
                new java.math.BigDecimal("100.00"), "MUR", null, "ana"))).isEqualTo("devolver-cobro:MRU01/GSX4AK:P-1");
        // The company billed: each change its own process — set, and taken away.
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.CompanyChanged("E-9", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", "ACME", "Acme Corporation Ltd", "ana")))
                .isEqualTo("asignar-empresa:MRU01/GSX4AK:E-9");
        assertThat(outbox("outboxUpstream")).filteredOn(p -> p.contains("asignar-empresa:MRU01/GSX4AK:E-9"))
                .singleElement().satisfies(p -> assertThat(p)
                        .contains("\"workflowDefinitionId\":\"asignar-empresa\"")
                        .contains("{\"name\":\"companyCode\",\"value\":\"ACME\"}")
                        .contains("{\"name\":\"companyName\",\"value\":\"Acme Corporation Ltd\"}"));
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.CompanyChanged("E-10", at,
                "MRU01", "GSX4AK", "GSX4AK", "XMAR", "39486034", null, null, "ana")))
                .isEqualTo("asignar-empresa:MRU01/GSX4AK:E-10");
        assertThat(outbox("outboxUpstream")).filteredOn(p -> p.contains("asignar-empresa:MRU01/GSX4AK:E-10"))
                .singleElement().satisfies(p -> assertThat(p).doesNotContain("companyCode"));
        // A walk-in neither the CRS nor Opera has yet: nothing to record now.
        assertThat(reception.on(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.GuestCheckedIn("E-4", at,
                "MRU01", "FO-6XDAWR", null, "XMAR", null, "205", 1, "ana"))).isNull();
    }

    void assertGateSignalled(String id, String gate) {
        var i = integration(id);
        assertThat(i.gate).isEqualTo(gate);
        assertThat(lifecycle.gateOpen(i)).isTrue();
        gates.look();
        assertThat(outbox("outboxUpstream")).anyMatch(p -> p.contains("\"messageName\":\"" + gate + "\"") && p.contains(i.processKey));
    }

    static final Pattern KEY = Pattern.compile("\"businessKey\":\"(proyectar-estancia:[^\"]+)\"");

    /** The «proyectar-estancia» processes asked of the engine, by business key, in order, each once. */
    List<String> stayKeys() {
        return outbox("outboxUpstream").stream().map(KEY::matcher).filter(java.util.regex.Matcher::find)
                .map(m -> m.group(1)).distinct().toList();
    }

    List<String> outbox(String binding) {
        return jdbc.queryForList("select payload from outbox_message where binding = ? order by seq", String.class, binding);
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }
}
