package io.mateu.ecdemo1.integrations.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.contracts.testing.Contracts;
import io.mateu.ecdemo1.integration.model.audit.AuditedAction;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand;
import io.mateu.ecdemo1.integration.model.notification.NotificationRequested;
import io.mateu.ecdemo1.integration.model.notification.NotificationType;
import io.mateu.ecdemo1.integration.model.pms.PmsReservationChanged;
import io.mateu.ecdemo1.integrations.config.StreamFunctions;
import io.mateu.ecdemo1.integrations.config.TolerantReader;
import io.mateu.ecdemo1.integrations.frontoffice.PmsReservationEvents;
import io.mateu.ecdemo1.integrations.outbox.Commands;
import io.mateu.ecdemo1.integrations.outbox.Outbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What integrations-service puts on the topics it produces to, as its outbox really writes it,
 * checked against each topic's schema (contracts/schemas); and every example of pms-reservations,
 * the one topic it reads, taken by its consumer.
 */
class IntegrationsContractsTest {

    static final Instant AT = Instant.parse("2026-11-12T09:30:00Z");

    /** The application's mapper, as Spring Boot's auto-configuration builds it. */
    static ObjectMapper bootMapper() {
        try (var context = new AnnotationConfigApplicationContext(JacksonAutoConfiguration.class)) {
            return context.getBean(ObjectMapper.class);
        }
    }

    final ObjectMapper boot = bootMapper();
    final io.mateu.ecdemo1.messaging.Outbox shared = mock(io.mateu.ecdemo1.messaging.Outbox.class);
    final Outbox outbox = new Outbox(shared, null, boot, Clock.fixed(AT, ZoneOffset.UTC));
    final Commands commands = new Commands(outbox);

    /** The payloads appended to a binding. */
    List<String> written(String binding, int count) {
        var payloads = ArgumentCaptor.forClass(String.class);
        verify(shared, times(count)).append(eq(binding), anyString(), anyString(), payloads.capture(), any());
        return payloads.getAllValues();
    }

    @Test
    void projectionRequestsAreWhatTheSchemaSays() {
        commands.project("MRU01", "12E45", "backfill:R1");

        written(Outbox.PROJECTIONS, 1).forEach(Contracts.topic("projection-requests")::assertValid);
    }

    @Test
    void partnerCommandsAreWhatTheErpTakes() {
        commands.resyncPartner("NORDTRAVEL");
        // The ERP's partner types are its own enum's names: the integration sends them as it names them.
        commands.importPartner("SUNTOURS", "TravelAgent", "Sun Tours", "16120700", "Agent");

        written(Outbox.PARTNER_COMMANDS, 2).forEach(Contracts.topic("partner-commands")::assertValid);
    }

    @Test
    void mappingCommandsAreWhatTheSchemaSays() {
        commands.defineHotel("MRU01", "XMAR", "ana");
        commands.definePartnerTypes("ana");
        commands.requestAgentProposal("MRU01");
        commands.resolveCauseIfOpen("MISSING_MAPPING:MRU01:ROOM_TYPE:STD-KING", "ana");
        commands.recordPartnerProfile("NORDTRAVEL", "16120699", "Agent");

        var payloads = written(Outbox.MAPPING_COMMANDS, 8);
        payloads.forEach(Contracts.topic("mapping-commands")::assertValid);
        // The chain's equivalences carry no hotel: null, as the schema allows.
        assertThat(payloads).anySatisfy(p -> assertThat(p).contains("\"hotelCode\":null"));
    }

    @Test
    void frontOfficeCataloguesAreWhatTheSchemaSays() {
        outbox.appendToFrontOffice(new FrontOfficeCommand.ReplaceCatalogue("CMD-1", "XMAR", List.of(
                new FrontOfficeCommand.CatalogueEntry(FrontOfficeCommand.CatalogueType.ROOM_TYPE, "KNG", "King", null),
                new FrontOfficeCommand.CatalogueEntry(FrontOfficeCommand.CatalogueType.ROOM, "101", "Room 101", "KNG"))));

        written(Outbox.FRONT_OFFICE_COMMANDS, 1).forEach(Contracts.topic("front-office-commands")::assertValid);
    }

    @Test
    void notificationsResolutionsAndAuditAreWhatTheirSchemasSay() {
        outbox.appendNotification(new NotificationRequested("N-1", NotificationType.INTEGRATION_NEEDS_ATTENTION,
                "MRU01", "integration:INT-7", "MRU01 needs attention", "The PMS does not answer", "/integrations",
                "integration:INT-7:connectivity", AT));
        outbox.appendResolution("integration:INT-7", "ana");
        outbox.appendAudit(new AuditedAction("A-1", AT, "integrations-service", "activate", "MRU01", "ana",
                "{\"integrationId\":\"INT-7\"}", true, null));

        written(Outbox.NOTIFICATIONS, 1).forEach(Contracts.topic("notifications")::assertValid);
        written(Outbox.RESOLUTIONS, 1).forEach(Contracts.topic("notification-resolutions")::assertValid);
        written(Outbox.AUDIT, 1).forEach(Contracts.topic("audit")::assertValid);
    }

    @Test
    void everyExampleOfFrontOfficeEventsIsTakenByTheConsumer() {
        var reception = mock(io.mateu.ecdemo1.integrations.frontoffice.ReceptionEvents.class);
        var consumer = new StreamFunctions(null, new TolerantReader(boot), reception).consumeFrontOfficeEvents();

        var examples = Contracts.topic("front-office-events").examples();
        examples.forEach(json -> consumer.accept(MessageBuilder.withPayload(json.getBytes()).build()));

        var read = ArgumentCaptor.forClass(io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent.class);
        verify(reception, times(examples.size())).on(read.capture());
        assertThat(read.getAllValues()).hasSize(9).allSatisfy(e -> {
            assertThat(e.eventId()).isNotBlank();
            assertThat(e.stayId()).isNotBlank();
            assertThat(e.pmsHotelCode()).isNotBlank();
        });
        assertThat(read.getAllValues()).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("GuestCheckedIn", "GuestCheckedOut", "NoShowReported", "ChargePosted", "ChargeVoided",
                        "PaymentTaken", "PaymentRefunded", "CompanyChanged", "StayClosed");
    }

    @Test
    void everyExampleOfPmsReservationsIsTakenByTheConsumer() {
        var events = mock(PmsReservationEvents.class);
        var consumer = new StreamFunctions(events, new TolerantReader(boot), null).consumePmsReservations();

        var examples = Contracts.topic("pms-reservations").examples();
        examples.forEach(json -> consumer.accept(MessageBuilder.withPayload(json.getBytes()).build()));

        var read = ArgumentCaptor.forClass(PmsReservationChanged.class);
        verify(events, times(examples.size())).on(read.capture());
        assertThat(read.getAllValues()).allSatisfy(e -> {
            assertThat(e.pmsHotelCode()).isNotBlank();
            assertThat(e.pmsReservationId()).isNotBlank();
            assertThat(e.locator()).isNotBlank();
            assertThat(e.occurredAt()).isNotNull();
        });
    }
}
