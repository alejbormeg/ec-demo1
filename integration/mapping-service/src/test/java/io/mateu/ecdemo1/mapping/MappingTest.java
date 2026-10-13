package io.mateu.ecdemo1.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mateu.ecdemo1.integration.model.mapping.CodeType;
import io.mateu.ecdemo1.mapping.dictionary.Dictionary;
import io.mateu.ecdemo1.mapping.store.CauseRecordRepository;
import io.mateu.ecdemo1.mapping.store.CauseStatus;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import io.mateu.ecdemo1.mapping.store.EntryStatus;
import io.mateu.ecdemo1.mapping.store.MappingEntryRepository;
import io.mateu.ecdemo1.mapping.store.WaiterRepository;
import io.mateu.ecdemo1.mapping.store.WaiterStatus;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mapping against a real Postgres and broker, with the CRS adapter played by a small HTTP
 * server. Steps are driven the way the engine drives them — a task on the mapping topic — and
 * observed the way the engine would see them: replies and messages on upstream.
 */
@SpringBootTest(properties = {"mapping.resend-after=2s", "mapping.resend-check=1s"})
@AutoConfigureMockMvc
@Testcontainers
class MappingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.1.7")
            // size=8g: unsized, a tmpfs gets half the Docker VM's memory, under Redpanda's 5 GiB free-space
            // floor, and every write is refused. It is a ceiling, not an allocation.
            .withTmpFs(Map.of("/var/lib/redpanda/data", "rw,size=8g"));

    static HttpServer crs;
    /** What the integrations service answers for PMI01's integration; null for "no integration". */
    static volatile String integrationStatus = "ACTIVE";

    static String reservation(String locator, String partner) {
        return """
                {"hotelCode":"PMI01","locator":"%s","version":2,"status":"CONFIRMED","channelCode":"WEB",
                 "partnerCode":%s,"arrival":"2026-10-05","departure":"2026-10-07","currency":"EUR",
                 "holder":{"firstName":"Ana","lastName":"García","type":"ADULT"},
                 "rooms":[{"line":1,"roomTypeCode":"DBL","ratePlanCode":"BAR","boardCode":"AD","adults":2,
                           "childrenAges":[],"guests":[],"nightlyRates":[{"date":"2026-10-05","amount":135}]}],
                 "payments":[],"totalAmount":135}""".formatted(locator, partner == null ? "null" : "\"" + partner + "\"");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        crs = HttpServer.create(new InetSocketAddress(0), 0);
        crs.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var parts = path.split("/");
            String body = path.startsWith("/reservations/PMI01/")
                    ? reservation(parts[3], parts[3].startsWith("P-") ? "NORDTRAVEL" : null)
                    : path.equals("/integrations/hotels/PMI01") && integrationStatus != null
                    ? """
                      {"id":"I-1","crsHotelCode":"PMI01","pmsHotelCode":"RIUPMI","status":"%s"}""".formatted(integrationStatus)
                    : null;
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        crs.start();
        registry.add("CRS_INTEGRATION_URL", () -> "http://localhost:" + crs.getAddress().getPort());
        registry.add("INTEGRATIONS_URL", () -> "http://localhost:" + crs.getAddress().getPort());
        registry.add("KAFKA_BROKERS", redpanda::getBootstrapServers);
    }

    @AfterAll
    static void stop() {
        crs.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    Dictionary dictionary;
    @Autowired
    MappingEntryRepository entries;
    @Autowired
    CauseRecordRepository causes;
    @Autowired
    WaiterRepository waiters;

    @Test
    void aReservationMissingCodesWaitsOnAllOfThemAndResumesOnlyWhenTheLastIsApproved() throws Exception {
        var key = "proyectar-reserva:PMI01/L1:E1";
        var reply = runStep("prepare-reservation", "L1", key);
        assertThat(reply.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(variables(reply)).containsEntry("prepareOutcome", "WAIT");

        assertThat(causes.findAll()).extracting(c -> c.causeKey).contains(
                "MISSING_MAPPING:PMI01:HOTEL:PMI01", "MISSING_MAPPING:PMI01:CHANNEL:WEB",
                "MISSING_MAPPING:PMI01:ROOM_TYPE:DBL", "MISSING_MAPPING:PMI01:RATE_PLAN:BAR",
                "MISSING_MAPPING:PMI01:BOARD:AD");
        var expected = List.of("HOTEL:PMI01", "CHANNEL:WEB", "ROOM_TYPE:DBL", "RATE_PLAN:BAR", "BOARD:AD");
        assertThat(consume("notifications", r -> r.value().contains("CAUSE_OPENED")
                && expected.stream().anyMatch(c -> r.value().contains("MISSING_MAPPING:PMI01:" + c)), 6, 10))
                .hasSize(5);

        define(CodeType.HOTEL, null, "PMI01", "OPERA-H1", Map.of());
        define(CodeType.CHANNEL, null, "WEB", "WEB", Map.of("marketCode", "LEIS"));
        define(CodeType.ROOM_TYPE, "PMI01", "DBL", "DBLK", Map.of());
        define(CodeType.RATE_PLAN, null, "BAR", "RACK", Map.of());
        assertThat(waiters.findById(key)).get().extracting(w -> w.status).isEqualTo(WaiterStatus.WAITING);
        define(CodeType.BOARD, null, "AD", "BB", Map.of());

        assertThat(waiters.findById(key)).get().extracting(w -> w.status).isEqualTo(WaiterStatus.RELEASED);
        // At least once: this class resends every two seconds until the process answers.
        assertThat(consume("upstream", r -> r.value().contains("causes-resolved") && r.value().contains(key), 1, 15))
                .isNotEmpty().allSatisfy(r -> assertThat(json(r.value()).get("type").asText()).isEqualTo("message-received"));

        // The process resumes and asks for its successor — twice, as a retried step would.
        runStep("relaunch", "L1", key);
        runStep("relaunch", "L1", key);
        var successors = consume("upstream", r -> r.value().contains("process-creation-requested")
                && r.value().contains(key + ">r"), 2, 8);
        assertThat(successors).singleElement().satisfies(r -> {
            var start = json(r.value());
            assertThat(start.get("workflowDefinitionId").asText()).isEqualTo("proyectar-reserva");
            assertThat(variables(start)).containsEntry("processKey", key + ">r").containsEntry("locator", "L1")
                    .doesNotContainKey("prepareOutcome");
        });

        // The successor finds everything mapped.
        assertThat(variables(runStep("prepare-reservation", "L1", key + ">r"))).containsEntry("prepareOutcome", "OK");
    }

    @Test
    void theResumeMessageIsSentAgainUntilTheProcessAnswers() throws Exception {
        var key = "proyectar-reserva:PMI01/L2:E2";
        var cause = "MISSING_MAPPING:PMI01:BOARD:ZZ";
        dictionary.propose(new Dictionary.Proposal(CodeType.BOARD, null, "ZZ", "X", Map.of(), null, null), "t");
        waitOn(key, cause);
        approveProposalFor(CodeType.BOARD, "ZZ");

        var signals = consume("upstream", r -> r.value().contains("causes-resolved") && r.value().contains(key), 2, 10);
        assertThat(signals.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void proposingAgainWhatIsAlreadyWaitingRecordsNothingNewButAnAlternativeIsRecorded() {
        var first = dictionary.propose(new Dictionary.Proposal(CodeType.ROOM_TYPE, "DUP01", "DUPX", "XDUP", Map.of(), 0.6, "first"), "agent");
        var again = dictionary.propose(new Dictionary.Proposal(CodeType.ROOM_TYPE, "DUP01", "DUPX", "XDUP", Map.of(), 0.9, "second"), "agent");
        var other = dictionary.propose(new Dictionary.Proposal(CodeType.ROOM_TYPE, "DUP01", "DUPX", "YDUP", Map.of(), null, null), "agent");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getConfidence()).isEqualTo(0.9);
        assertThat(again.getRationale()).isEqualTo("second");
        assertThat(other.getId()).isNotEqualTo(first.getId());
        assertThat(entries.findAll()).filteredOn(e -> "DUPX".equals(e.getSourceCode())).hasSize(2);
    }

    @Test
    void aPropertyExceptionWinsOverTheChainAndApprovingSupersedesThePreviousVersion() {
        define(CodeType.RATE_PLAN, null, "NRF", "NONREF", Map.of());
        define(CodeType.RATE_PLAN, "CUN01", "NRF", "NRF-MX", Map.of());
        assertThat(dictionary.resolve("PMI01", CodeType.RATE_PLAN, "NRF")).get().extracting(t -> t.targetCode()).isEqualTo("NONREF");
        assertThat(dictionary.resolve("CUN01", CodeType.RATE_PLAN, "NRF")).get().extracting(t -> t.targetCode()).isEqualTo("NRF-MX");

        define(CodeType.RATE_PLAN, null, "NRF", "NONREF2", Map.of());
        assertThat(dictionary.resolve("PMI01", CodeType.RATE_PLAN, "NRF")).get().extracting(t -> t.targetCode()).isEqualTo("NONREF2");
        assertThat(entries.findAll()).filteredOn(e -> e.getSourceCode().equals("NRF") && e.getHotelCode() == null)
                .extracting(e -> e.getStatus() + "@" + e.getEntryVersion())
                .containsExactlyInAnyOrder("SUPERSEDED@1", "APPROVED@2");
    }

    @Test
    void aMissingPartnerBlocksUntilItsProfileIsRecorded() throws Exception {
        for (var type : List.of(CodeType.HOTEL, CodeType.CHANNEL, CodeType.ROOM_TYPE, CodeType.RATE_PLAN, CodeType.BOARD)) {
            var code = switch (type) { case HOTEL -> "PMI01"; case CHANNEL -> "WEB"; case ROOM_TYPE -> "DBL"; case RATE_PLAN -> "BAR"; default -> "AD"; };
            if (dictionary.resolve("PMI01", type, code).isEmpty()) {
                define(type, type == CodeType.ROOM_TYPE ? "PMI01" : null, code, "X-" + code, Map.of());
            }
        }
        var key = "proyectar-reserva:PMI01/P-1:E3";
        assertThat(variables(runStep("prepare-reservation", "P-1", key))).containsEntry("prepareOutcome", "WAIT");
        assertThat(causes.findById("MISSING_PARTNER:NORDTRAVEL")).get().extracting(c -> c.status).isEqualTo(CauseStatus.OPEN);

        runStepWith("record-partner-profile", "proyectar-interlocutor:partner/NORDTRAVEL:E9", List.of(
                new Variable("partnerCode", "NORDTRAVEL"), new Variable("pmsProfileIds", "TA-100"),
                new Variable("pmsProfileType", "TRAVEL_AGENT"), new Variable("version", "1")));

        assertThat(causes.findById("MISSING_PARTNER:NORDTRAVEL")).get().extracting(c -> c.status).isEqualTo(CauseStatus.RESOLVED);
        assertThat(waiters.findById(key)).get().extracting(w -> w.status).isEqualTo(WaiterStatus.RELEASED);
    }

    @Test
    void anAgentProposalIsNotInForceUntilAPersonApprovesIt() {
        var proposal = dictionary.propose(new Dictionary.Proposal(CodeType.PAYMENT_METHOD, null, "AMEX", "AX",
                Map.of(), 0.9, "American Express is AX in the PMS"), "agent");
        assertThat(dictionary.resolve("PMI01", CodeType.PAYMENT_METHOD, "AMEX")).isEmpty();
        dictionary.approve(proposal.getId(), "Miguel");
        assertThat(dictionary.resolve("PMI01", CodeType.PAYMENT_METHOD, "AMEX")).get().extracting(t -> t.targetCode()).isEqualTo("AX");
        assertThat(entries.findById(proposal.getId())).get().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(EntryStatus.APPROVED);
            assertThat(e.getProposedBy()).isEqualTo("agent");
            assertThat(e.getDecidedBy()).isEqualTo("Miguel");
        });
    }

    @Autowired
    io.mateu.ecdemo1.mapping.causes.Causes causesService;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void thePartnerAPmsProfileIsIsFoundByTheProfile() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/partner-profiles/ACME")
                        .contentType("application/json").content("""
                                {"pmsProfileId":"77001","profileType":"Company"}"""))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/partner-profiles")
                        .param("pmsProfileId", "77001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("ACME"))
                .andExpect(jsonPath("$.profileType").value("Company"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/partner-profiles")
                        .param("pmsProfileId", "nobody"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolvingACauseAndDecidingTheLastProposalCloseWhatTheyAnnounced() throws Exception {
        jdbc.update("delete from outbox_message where binding = 'resolutions'");
        var entry = dictionary.propose(new Dictionary.Proposal(CodeType.ROOM_TYPE, "RES01", "RESX", "XRES", Map.of(), null, null), "agent");
        entries.findByStatusOrderByCreatedAtDesc(io.mateu.ecdemo1.mapping.store.EntryStatus.PROPOSED).stream()
                .filter(e -> !e.getId().equals(entry.getId())).forEach(e -> dictionary.reject(e.getId(), "cleanup"));
        jdbc.update("delete from outbox_message where binding = 'resolutions'");
        dictionary.approve(entry.getId(), "Ana García");

        var subjects = jdbc.queryForList("select message_key from outbox_message where binding = 'resolutions' order by seq", String.class);
        // The approval resolves the cause of the code, if there was one, and it was the last proposal.
        assertThat(subjects).contains(io.mateu.ecdemo1.mapping.proposals.ProposalAnnouncer.SUBJECT);
        var payload = jdbc.queryForObject("select payload from outbox_message where binding = 'resolutions' and message_key = ? order by seq desc limit 1",
                String.class, io.mateu.ecdemo1.mapping.proposals.ProposalAnnouncer.SUBJECT);
        assertThat(objectMapper.readValue(payload, io.mateu.ecdemo1.integration.model.notification.NotificationResolved.class).resolvedBy())
                .isEqualTo("Ana García");
    }

    @Test
    void anEquivalenceInForceCanBeWithdrawnAndTheCodeHasNoneAgain() throws Exception {
        var entry = dictionary.define(new Dictionary.Proposal(CodeType.BOARD, "WDR01", "WX", "SA", Map.of(), null, null), "Ana García");
        assertThat(dictionary.resolve("WDR01", CodeType.BOARD, "WX")).isPresent();
        jdbc.update("delete from outbox_message where binding = 'audit'");

        var withdrawn = dictionary.withdraw(entry.getId(), "Luis Pons");

        assertThat(withdrawn.getStatus()).isEqualTo(io.mateu.ecdemo1.mapping.store.EntryStatus.WITHDRAWN);
        assertThat(withdrawn.getDecidedBy()).isEqualTo("Luis Pons");
        assertThat(dictionary.resolve("WDR01", CodeType.BOARD, "WX")).isEmpty();
        // Only what is in force can be withdrawn — and the refusal is audited too.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dictionary.withdraw(entry.getId(), "Luis Pons"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WITHDRAWN");
        var audited = jdbc.queryForList("select payload from outbox_message where binding = 'audit' order by seq", String.class);
        assertThat(audited).hasSize(2).allMatch(p -> p.contains("\"action\":\"Withdraw mapping\""));
        assertThat(audited.get(0)).contains("\"succeeded\":true").contains("Luis Pons");
        assertThat(audited.get(1)).contains("\"succeeded\":false");
    }

    @Test
    void mappingDecisionsAreAuditedAndARefusalToo() throws Exception {
        jdbc.update("delete from outbox_message where binding = 'audit'");
        var entry = dictionary.propose(new Dictionary.Proposal(CodeType.ROOM_TYPE, "AUD01", "AUDX", "XAUD", Map.of(), null, null), "agent");
        dictionary.approve(entry.getId(), "Ana García");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dictionary.reject(entry.getId(), "Luis"))
                .isInstanceOf(IllegalStateException.class);

        var audited = new java.util.ArrayList<io.mateu.ecdemo1.integration.model.audit.AuditedAction>();
        for (var payload : jdbc.queryForList("select payload from outbox_message where binding = 'audit' order by seq", String.class)) {
            audited.add(objectMapper.readValue(payload, io.mateu.ecdemo1.integration.model.audit.AuditedAction.class));
        }
        assertThat(audited).extracting(a -> a.action(), a -> a.by(), a -> a.hotelCode(), a -> a.succeeded())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Propose mapping", "agent", "AUD01", true),
                        org.assertj.core.groups.Tuple.tuple("Approve mapping", "Ana García", "AUD01", true),
                        org.assertj.core.groups.Tuple.tuple("Reject mapping", "Luis", "AUD01", false));
        assertThat(audited.get(1).response()).contains("AUDX → XAUD: APPROVED (v1)");
        assertThat(audited).extracting(a -> a.service()).containsOnly("mapping");
    }

    @Autowired
    org.springframework.ai.tool.ToolCallbackProvider mappingTools;

    @Autowired
    io.mateu.ecdemo1.mapping.mcp.MappingMcpTools tools;

    @Test
    void theMcpServerOffersEveryToolTheAgentNeeds() {
        // Spring AI drops a tool it cannot describe — one returning Object — with only a warning at
        // startup. This is where that would show.
        assertThat(java.util.Arrays.stream(mappingTools.getToolCallbacks()).map(t -> t.getToolDefinition().name()))
                .contains("listOpenCauses", "listPendingCodes", "getPmsCatalog", "proposeMappings", "listProposals",
                        "approveMapping", "rejectMapping", "resolveCause");
        // What the agent cannot match goes in unmatched — optional: a full proposal has nothing there.
        var propose = java.util.Arrays.stream(mappingTools.getToolCallbacks())
                .filter(t -> t.getToolDefinition().name().equals("proposeMappings")).findFirst().orElseThrow();
        var schema = propose.getToolDefinition().inputSchema().replaceAll("\\s", "");
        assertThat(schema).contains("\"unmatched\"").contains("\"reason\"").contains("\"required\":[\"proposals\"]");
    }

    @Test
    void theAgentsProposalsWaitForAPersonAndAnApprovalNeedsAName() {
        var answer = tools.proposeMappings(List.of(
                new Dictionary.Proposal(CodeType.BOARD, null, "MP", "HALFB", Map.of(), 0.95, "Media pensión is half board"),
                new Dictionary.Proposal(CodeType.BOARD, null, "TI", "ALLINC", Map.of(), 0.9, "Todo incluido is all inclusive")), null);
        assertThat(answer).startsWith("Proposed 2");
        assertThat(dictionary.resolve("PMI01", CodeType.BOARD, "MP")).isEmpty();
        var mp = tools.listProposals().stream().filter(p -> p.sourceCode().equals("MP")).findFirst().orElseThrow();
        assertThat(tools.approveMapping(mp.id(), "agent")).startsWith("Error");
        assertThat(tools.approveMapping(mp.id(), "Miguel")).startsWith("Approved BOARD MP → HALFB");
        assertThat(dictionary.resolve("PMI01", CodeType.BOARD, "MP")).isPresent();
        assertThat(consume("notifications", r -> r.value().contains("PROPOSAL_READY"), 1, 10)).isNotEmpty();
    }

    @Test
    void aReceptionProcessIsRelaunchedWithItsStay() throws Exception {
        var key = "registrar-checkin:MRU01/GSX4AK";
        var cause = "PMS_REJECTED:MRU01:GSX4AK:check-in-reservation";
        causesService.await(key, "registrar-checkin", "MRU01", "GSX4AK", List.of(
                        new Variable("definitionId", "registrar-checkin"), new Variable("processKey", key),
                        new Variable("hotelCode", "MRU01"), new Variable("locator", "GSX4AK"),
                        new Variable("pmsHotelCode", "XMAR"), new Variable("pmsReservationId", "39486034"),
                        new Variable("stayId", "GSX4AK"), new Variable("roomNumber", "205"),
                        new Variable("checkInOutcome", "WAIT")),
                List.of(new io.mateu.ecdemo1.integration.model.mapping.Cause(cause,
                        io.mateu.ecdemo1.integration.model.mapping.CauseType.PMS_REJECTED, "room dirty")));
        causesService.resolve(cause, "ana");

        causesService.relaunch(key);

        var successors = consume("upstream", r -> r.value().contains("process-creation-requested")
                && r.value().contains(key + ">r"), 1, 8);
        assertThat(successors).singleElement().satisfies(r -> assertThat(variables(json(r.value())))
                .containsEntry("pmsHotelCode", "XMAR").containsEntry("pmsReservationId", "39486034")
                .containsEntry("stayId", "GSX4AK").containsEntry("roomNumber", "205")
                .containsEntry("processKey", key + ">r").doesNotContainKey("checkInOutcome"));
    }

    @Test
    void aChargeProcessIsRelaunchedWithItsFolioLine() throws Exception {
        var key = "registrar-cargo:MRU01/GSX4AK:L-1";
        var cause = "PMS_REJECTED:MRU01:GSX4AK:post-charge";
        causesService.await(key, "registrar-cargo", "MRU01", "GSX4AK", List.of(
                        new Variable("definitionId", "registrar-cargo"), new Variable("processKey", key),
                        new Variable("hotelCode", "MRU01"), new Variable("locator", "GSX4AK"),
                        new Variable("pmsHotelCode", "XMAR"), new Variable("pmsReservationId", "39486034"),
                        new Variable("stayId", "GSX4AK"), new Variable("lineId", "L-1"),
                        new Variable("chargeKind", "CONSUMPTION"), new Variable("chargeCode", "MB-02"),
                        new Variable("description", "Minibar"), new Variable("amount", "12.50"),
                        new Variable("currency", "MUR"), new Variable("chargeOutcome", "WAIT")),
                List.of(new io.mateu.ecdemo1.integration.model.mapping.Cause(cause,
                        io.mateu.ecdemo1.integration.model.mapping.CauseType.PMS_REJECTED, "not in house")));
        causesService.resolve(cause, "ana");

        causesService.relaunch(key);

        var successors = consume("upstream", r -> r.value().contains("process-creation-requested")
                && r.value().contains(key + ">r"), 1, 8);
        assertThat(successors).singleElement().satisfies(r -> assertThat(variables(json(r.value())))
                .containsEntry("lineId", "L-1").containsEntry("chargeKind", "CONSUMPTION")
                .containsEntry("chargeCode", "MB-02").containsEntry("description", "Minibar")
                .containsEntry("amount", "12.50").containsEntry("currency", "MUR")
                .containsEntry("pmsReservationId", "39486034").doesNotContainKey("chargeOutcome"));
    }

    @Test
    void discardingAWaitingProcessCancelsItInTheEngineAuditsItAndItIsNeverResumed() throws Exception {
        var key = "proyectar-cancelacion:PMI01/D1/X:E-9";
        var cause = "NOT_YET_PROJECTED:PMI01:D1";
        causesService.await(key, "ENGINE-ID-9", "proyectar-cancelacion", "PMI01", "D1",
                List.of(new Variable("locator", "D1"), new Variable("hotelCode", "PMI01")),
                List.of(new io.mateu.ecdemo1.integration.model.mapping.Cause(cause,
                        io.mateu.ecdemo1.integration.model.mapping.CauseType.NOT_YET_PROJECTED, "test")));

        // A key with a slash in it: named by a query parameter, as a path could not carry it.
        mvc.perform(post("/causes/waiters/discard").param("processKey", key)
                        .param("reason", "Test booking cancelled before reaching the PMS").param("by", "Ana García"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineCancelRequested").value(true))
                .andExpect(jsonPath("$.causesLeftUnwaited[0]").value(cause));

        var waiter = waiters.findById(key).orElseThrow();
        assertThat(waiter.getStatus()).isEqualTo(WaiterStatus.DISCARDED);
        assertThat(waiter.getFinishedBy()).isEqualTo("Ana García");
        assertThat(waiter.getReason()).isEqualTo("Test booking cancelled before reaching the PMS");
        var cancellations = consume("upstream", r -> r.value().contains("process-cancellation-requested")
                && r.value().contains("ENGINE-ID-9"), 1, 15);
        assertThat(cancellations).singleElement().satisfies(r -> assertThat(r.key()).isEqualTo("ENGINE-ID-9"));
        var audited = jdbc.queryForList("select payload from outbox_message where binding = 'audit' and payload like ?",
                String.class, "%" + key + "%");
        assertThat(audited).singleElement().satisfies(p -> assertThat(p).contains("Discard process").contains("Ana García")
                .contains("Test booking cancelled"));

        // Resolving the cause afterwards resumes nothing: the discarded process is not sent the message.
        causesService.resolve(cause, "t");
        causesService.resendSilent();
        assertThat(consume("upstream", r -> r.value().contains("causes-resolved") && r.value().contains(key), 1, 4)).isEmpty();
        assertThat(waiters.findById(key).orElseThrow().getStatus()).isEqualTo(WaiterStatus.DISCARDED);
    }

    @Test
    void aReleasedProcessSilentForLongerThanTheResendWindowIsNotSentAnythingAgain() throws Exception {
        var key = "proyectar-reserva:PMI01/L9:E9";
        var cause = "MISSING_MAPPING:PMI01:BOARD:GONE";
        waitOn(key, cause);
        causesService.resolve(cause, "t");
        // Released seven hours ago, over the six the resend lasts: the engine cancelled or finished it.
        jdbc.update("update waiter set released_at = now() - interval '7 hours', last_signal_at = now() - interval '7 hours' where process_key = ?", key);
        var before = consume("upstream", r -> r.value().contains("causes-resolved") && r.value().contains(key), 100, 3).size();

        Thread.sleep(3000);   // longer than resend-check and resend-after in this test

        assertThat(consume("upstream", r -> r.value().contains("causes-resolved") && r.value().contains(key), 100, 3))
                .hasSize(before);
        assertThat(waiters.findById(key).orElseThrow().getStatus()).isEqualTo(WaiterStatus.RELEASED);
    }

    void waitOn(String processKey, String causeKey) {
        causesService.await(processKey, "proyectar-reserva", "PMI01", "L2",
                List.of(new Variable("locator", "L2"), new Variable("hotelCode", "PMI01")),
                List.of(new io.mateu.ecdemo1.integration.model.mapping.Cause(causeKey,
                        io.mateu.ecdemo1.integration.model.mapping.CauseType.MISSING_MAPPING, "test")));
    }

    void approveProposalFor(CodeType type, String code) {
        entries.findAll().stream().filter(e -> e.getType() == type && e.getSourceCode().equals(code) && e.getStatus() == EntryStatus.PROPOSED)
                .forEach(e -> dictionary.approve(e.getId(), "t"));
    }

    void define(CodeType type, String hotel, String code, String target, Map<String, String> attributes) {
        dictionary.define(new Dictionary.Proposal(type, hotel, code, target, attributes, null, null), "test");
    }

    @Test
    void enteringAnEquivalenceAlreadyInForceFromTheChainChangesNothing() throws Exception {
        define(CodeType.HOTEL, null, "MRU01", "XMAR", Map.of());
        var before = entries.count();

        // The integration enters the hotel's own equivalence; the chain's already says the same.
        mvc.perform(post("/entries/definitions").param("by", "integration MRU01").contentType("application/json")
                        .content("""
                                {"type":"HOTEL","hotelCode":"MRU01","sourceCode":"MRU01","targetCode":"XMAR","attributes":{}}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetCode").value("XMAR"));
        assertThat(entries.count()).isEqualTo(before);
    }

    @Test
    void aHotelWhoseIntegrationIsNotActiveHoldsItsReservationsButNotItsBackfill() throws Exception {
        define(CodeType.HOTEL, null, "PMI01", "OPERA-H1", Map.of());
        define(CodeType.CHANNEL, null, "WEB", "WEB", Map.of("marketCode", "LEIS"));
        define(CodeType.ROOM_TYPE, "PMI01", "DBL", "DBLK", Map.of());
        define(CodeType.RATE_PLAN, null, "BAR", "RACK", Map.of());
        define(CodeType.BOARD, null, "AD", "BB", Map.of());
        integrationStatus = "BACKFILLING";
        try {
            var live = "proyectar-reserva:PMI01/L7:E7";
            assertThat(variables(runStep("prepare-reservation", "L7", live))).containsEntry("prepareOutcome", "WAIT");
            assertThat(causes.findById("INTEGRATION_INACTIVE:PMI01")).get()
                    .satisfies(c -> assertThat(c.status).isEqualTo(CauseStatus.OPEN));

            var backfill = "proyectar-reserva:PMI01/L8:backfill:R1";
            assertThat(variables(runStepWith("prepare", backfill,
                    List.of(new Variable("locator", "L8"), new Variable("origin", "backfill:R1")))))
                    .containsEntry("prepareOutcome", "OK");

            integrationStatus = "ACTIVE";
            mvc.perform(post("/causes/resolve-if-open").param("key", "INTEGRATION_INACTIVE:PMI01").param("by", "integration"))
                    .andExpect(status().isOk());
            assertThat(waiters.findById(live)).get().extracting(w -> w.status).isEqualTo(WaiterStatus.RELEASED);
        } finally {
            integrationStatus = "ACTIVE";
        }
    }

    @Test
    void theBackfillsPrePassListsWhatItsReservationsLackMostBlockingFirst() throws Exception {
        define(CodeType.ROOM_TYPE, "PMI01", "DBL", "DBLK", Map.of());
        var gaps = json(mvc.perform(post("/gaps").contentType(MediaType.APPLICATION_JSON).content("""
                        {"hotelCode":"PMI01","reservations":12,
                         "codes":[{"type":"ROOM_TYPE","code":"DBL","reservations":12},
                                  {"type":"BOARD","code":"XX","reservations":3},
                                  {"type":"ROOM_TYPE","code":"ZZZ","reservations":9}],
                         "partners":[{"partnerCode":"NOBODY","reservations":5}]}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var listed = new ArrayList<String>();
        gaps.forEach(g -> listed.add(g.get("kind").asText() + ":" + g.get("code").asText() + ":" + g.get("reservations").asInt()));
        assertThat(listed).containsExactly("MAPPING:ZZZ:9", "PARTNER:NOBODY:5", "MAPPING:XX:3");
    }

    JsonNode runStep(String step, String locator, String processKey) throws Exception {
        return runStepWith(step, processKey, List.of(new Variable("locator", locator)));
    }

    JsonNode runStepWith(String step, String processKey, List<Variable> extra) throws Exception {
        var variables = new ArrayList<>(List.of(new Variable("hotelCode", "PMI01"), new Variable("processKey", processKey),
                new Variable("definitionId", processKey.substring(0, processKey.indexOf(':'))), new Variable("version", "2")));
        variables.addAll(extra);
        var taskId = UUID.randomUUID().toString();
        var definition = processKey.substring(0, processKey.indexOf(':'));
        var stepId = switch (step) {
            case "prepare", "prepare-reservation", "prepare-cancellation", "prepare-partner" -> "prepare";
            case "relaunch" -> "relaunch-prepare";
            default -> step;
        };
        // The contract the step names in its definition, which the engine dispatches as the taskId.
        var contract = switch (stepId) {
            case "prepare" -> switch (definition) {
                case "proyectar-cancelacion" -> "prepare-cancellation";
                case "proyectar-interlocutor" -> "prepare-partner";
                default -> "prepare-reservation";
            };
            case "relaunch-prepare" -> "relaunch-process";
            default -> stepId;
        } + "@1";
        var task = new TaskExecutionRequested(taskId, "P-" + taskId, definition, stepId, contract, variables);
        send("mapping", task.processId(), objectMapper.writerFor(DomainEvent.class).writeValueAsString(task));
        return consume("upstream", r -> r.value().contains(taskId) && r.value().contains("task-status-changed"), 1, 20)
                .stream().findFirst().map(r -> json(r.value()))
                .orElseThrow(() -> new AssertionError("No reply to " + step));
    }

    static Map<String, String> variables(JsonNode event) {
        var map = new java.util.HashMap<String, String>();
        event.get("variables").forEach(v -> map.put(v.get("name").asText(), v.get("value").asText()));
        return map;
    }

    JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void send(String topic, String key, String value) {
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<ConsumerRecord<String, String>> consume(String topic, Predicate<ConsumerRecord<String, String>> match,
                                                        int enough, int seconds) {
        var found = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plusSeconds(seconds);
            while (found.size() < enough && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(record)) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }
}
