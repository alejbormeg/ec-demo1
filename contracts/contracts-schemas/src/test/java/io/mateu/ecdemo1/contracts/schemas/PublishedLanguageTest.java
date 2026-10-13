package io.mateu.ecdemo1.contracts.schemas;

import io.mateu.ecdemo1.contracts.testing.SchemaFiles;
import io.mateu.ecdemo1.contracts.testing.TopicSpec;
import io.mateu.ecdemo1.integration.model.audit.AuditedAction;
import io.mateu.ecdemo1.integration.model.command.CustomerCommand;
import io.mateu.ecdemo1.integration.model.command.MappingCommand;
import io.mateu.ecdemo1.integration.model.command.ProjectReservation;
import io.mateu.ecdemo1.integration.model.command.ReportNoShow;
import io.mateu.ecdemo1.integration.model.customer.CustomerChanged;
import io.mateu.ecdemo1.integration.model.customer.CustomerEvent;
import io.mateu.ecdemo1.integration.model.customer.CustomerNoticeChanged;
import io.mateu.ecdemo1.integration.model.customer.CustomersMerged;
import io.mateu.ecdemo1.integration.model.customer.GoldenRecord;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent;
import io.mateu.ecdemo1.integration.model.mapping.CodeType;
import io.mateu.ecdemo1.integration.model.notice.NoticeChanged;
import io.mateu.ecdemo1.integration.model.notification.NotificationRequested;
import io.mateu.ecdemo1.integration.model.notification.NotificationResolved;
import io.mateu.ecdemo1.integration.model.notification.NotificationType;
import io.mateu.ecdemo1.integration.model.pms.PmsReservationChanged;
import io.mateu.ecdemo1.integration.model.registration.RegistrationRuleChanged;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * The topics whose messages are the contracts' records: one {@link TopicSpec} each, whose schema is
 * contracts/schemas/&lt;topic&gt;/v&lt;N&gt;.schema.json — this fails when the committed file is not
 * what the records say ({@code -Dcontracts.write=true} writes it). The examples are what every
 * consumer's contract test parses: one per variant, with the values a real message carries.
 */
class PublishedLanguageTest {

    static final Instant AT = Instant.parse("2026-11-12T09:30:00Z");

    static List<TopicSpec> topics() {
        return List.of(
                TopicSpec.topic("customers")
                        .describedAs("The MDM's customers: a golden record created or changed, two customers merged. "
                                + "Carries the reservations the customer is on, so a reader can re-project them.")
                        .ownedBy("customer-mdm-service").keyedBy("customerId")
                        .producedBy("customer-mdm-service").consumedBy("crs-integration-service", "front-office", "customer-history")
                        .messages(CustomerEvent.class)
                        .example(new CustomerChanged("E-1", AT, "C-00042", 3,
                                        new GoldenRecord("Ana", "García", "ana@example.com", "+34600000000", "ES",
                                                LocalDate.of(1990, 5, 17), "PASSPORT", "X1234567",
                                                List.of(new GoldenRecord.IdentityDocument("PASSPORT", "X1234567", "ES"),
                                                        new GoldenRecord.IdentityDocument("DNI", "12345678Z", "ES")),
                                                new GoldenRecord.Profile("F", "es", "Sevilla", "Calle Mayor 1", "Palma",
                                                        "07001", "Illes Balears", "ES", null, "RC00000042", true)),
                                        true, "CR-7", "APPROVED", null, List.of("MRU01/12E45")),
                                new CustomersMerged("E-2", AT, "C-00042", 4,
                                        new GoldenRecord("Ana", "García", "ana@example.com", null, "ES", null, null, null),
                                        "C-00051", List.of("MRU01/12E45", "MRU01/0099"))),
                TopicSpec.topic("customer-notices")
                        .describedAs("A customer's reception notices, as Salesforce (their master) has them: created, "
                                + "changed or deactivated — the whole notice each time; the highest version wins.")
                        .ownedBy("customer-mdm-service").keyedBy("customerId")
                        .producedBy("customer-mdm-service").consumedBy("notices")
                        .messages(CustomerNoticeChanged.class)
                        .example(new CustomerNoticeChanged("E-4", AT, "AV-7F3A2C", 2, "C-00042",
                                        "Pedir el pasaporte original: el del CRS está caducado",
                                        CustomerNoticeChanged.NoticeType.BLOCKING, LocalDate.of(2026, 11, 1), null,
                                        List.of(CustomerNoticeChanged.NoticeMoment.CHECK_IN), true, "500d1000009XyZAAA0"),
                                new CustomerNoticeChanged("E-5", AT, "AV-7F3A2D", 5, "C-00042",
                                        "Cliente alérgico a los frutos secos", CustomerNoticeChanged.NoticeType.IMPORTANT,
                                        null, null, List.of(CustomerNoticeChanged.NoticeMoment.CHECK_IN,
                                                CustomerNoticeChanged.NoticeMoment.STAY), false, "500d1000009XyZBAA0")),
                TopicSpec.topic("notices")
                        .describedAs("The reception notices the desk must know of a customer, a reservation or a "
                                + "partner — the notices service's own and the customers' it takes from Salesforce "
                                + "(customer-notices) — the whole notice each time; the highest version wins.")
                        .ownedBy("notices").keyedBy("subjectType:subjectId")
                        .producedBy("notices").consumedBy("front-office")
                        .messages(NoticeChanged.class)
                        .example(new NoticeChanged("E-6", AT, "AV-R-1", 1, NoticeChanged.SubjectType.RESERVATION,
                                        "12E45", null, "MRU01", "Cuna en la habitación: la pidieron al reservar",
                                        NoticeChanged.NoticeType.IMPORTANT, LocalDate.of(2026, 11, 1), null,
                                        List.of(NoticeChanged.NoticeMoment.PRE_ARRIVAL,
                                                NoticeChanged.NoticeMoment.CHECK_IN), true, "NOTICES", null),
                                new NoticeChanged("E-7", AT, "AV-P-1", 3, NoticeChanged.SubjectType.PARTNER,
                                        "NORDTRAVEL", "Nordic Travel Group AB", null,
                                        "Bono obligatorio: sin bono no se hace el check-in",
                                        NoticeChanged.NoticeType.BLOCKING, null, null,
                                        List.of(NoticeChanged.NoticeMoment.CHECK_IN), true, "NOTICES", null),
                                new NoticeChanged("E-8", AT, "AV-7F3A2C", 2, NoticeChanged.SubjectType.CUSTOMER,
                                        "C-00042", null, null, "Cliente alérgico a los frutos secos",
                                        NoticeChanged.NoticeType.IMPORTANT, null, null,
                                        List.of(NoticeChanged.NoticeMoment.CHECK_IN, NoticeChanged.NoticeMoment.IN_HOUSE),
                                        false, "SALESFORCE", "500d1000009XyZBAA0")),
                TopicSpec.topic("registration-rules")
                        .describedAs("Which of a guest's data a destination's law requires when the hotel registers "
                                + "them, by the hotel's country or the hotel, the guest's nationality, age and role — "
                                + "kept in the control plane by compliance; the whole rule each time, the highest "
                                + "version wins.")
                        .ownedBy("registration-rules").keyedBy("scope:scopeCode")
                        .producedBy("registration-rules").consumedBy("front-office")
                        .messages(RegistrationRuleChanged.class)
                        .example(new RegistrationRuleChanged("E-9", AT, "RR-ES-1", 2, "España · registro de viajeros",
                                        RegistrationRuleChanged.Scope.COUNTRY, "ES",
                                        RegistrationRuleChanged.NationalityMatch.ANY, List.of(), 14, null,
                                        RegistrationRuleChanged.Role.ANY,
                                        List.of(RegistrationRuleChanged.Field.DOCUMENT_TYPE,
                                                RegistrationRuleChanged.Field.DOCUMENT_NUMBER,
                                                RegistrationRuleChanged.Field.NATIONALITY,
                                                RegistrationRuleChanged.Field.BIRTH_DATE,
                                                RegistrationRuleChanged.Field.ADDRESS),
                                        List.of(), List.of(RegistrationRuleChanged.Moment.CHECK_IN,
                                                RegistrationRuleChanged.Moment.ONLINE_CHECK_IN),
                                        "RD 933/2021 · SES.Hospedajes", LocalDate.of(2026, 1, 1), null, true),
                                new RegistrationRuleChanged("E-10", AT, "RR-MRU01-1", 1, "Fuera de la UE: el documento",
                                        RegistrationRuleChanged.Scope.HOTEL, "MRU01",
                                        RegistrationRuleChanged.NationalityMatch.NOT_IN, List.of("EU", "MU"), null, null,
                                        RegistrationRuleChanged.Role.HOLDER,
                                        List.of(RegistrationRuleChanged.Field.DOCUMENT_EXPIRY,
                                                RegistrationRuleChanged.Field.DOCUMENT_ISSUING_COUNTRY),
                                        List.of(RegistrationRuleChanged.Field.ADDRESS),
                                        List.of(RegistrationRuleChanged.Moment.CHECK_IN), "Ejemplo", null, null, false)),
                TopicSpec.topic("customer-commands")
                        .describedAs("What the MDM is asked without waiting: a change to a customer proposed, an "
                                + "identity scanned at check-in recorded, a kárdex filled in at the desk recorded.")
                        .ownedBy("customer-mdm-service").keyedBy("customerId, or hotelCode/locator for a scan with no customer")
                        .producedBy("front-office").consumedBy("customer-mdm-service")
                        .messages(CustomerCommand.class)
                        .example(new CustomerCommand.ProposeChange("CMD-1", "C-00042", "Ana García", "ana@example.com",
                                        "+34600000000", "X1234567", "front-office"),
                                new CustomerCommand.RecordScannedIdentity("CMD-2", "MRU01", "12E45", "ST-9", 1, null,
                                        "Ana", "García", "PASSPORT", "X1234567", LocalDate.of(1990, 5, 17), "ES",
                                        "front-office", "ES", LocalDate.of(2031, 3, 1), "C-00042"),
                                new CustomerCommand.RecordKardex("CMD-5", "MRU01", "12E45", "ST-9", 1, "C-00042",
                                        "Ana", "García", "F", LocalDate.of(1990, 5, 17), "Sevilla", "ES", "es",
                                        "Calle Mayor 1", "Palma", "07001", "Illes Balears", "ES", null, "PASSPORT",
                                        "X1234567", LocalDate.of(2021, 3, 1), LocalDate.of(2031, 3, 1), "RC00000042",
                                        true, false, "front-office")),
                TopicSpec.topic("projection-requests")
                        .describedAs("A reservation to project again by the same path a change in the CRS takes — "
                                + "a backfill's, once per reservation.")
                        .ownedBy("crs-integration-service").keyedBy("hotelCode/locator")
                        .producedBy("integrations-service").consumedBy("crs-integration-service")
                        .messages(ProjectReservation.class)
                        .example(new ProjectReservation("CMD-3", "MRU01", "12E45", "backfill:R1")),
                TopicSpec.topic("no-show-reports")
                        .describedAs("The hotel says a reservation's guests have not arrived; the CRS cancels it as a no-show. "
                                + "The front office no longer sends it: its no-show goes up to the PMS (front-office-events), "
                                + "which reports it to the CRS through the engine. Kept for whoever reports one directly.")
                        .ownedBy("crs-integration-service").keyedBy("hotelCode/locator")
                        .consumedBy("crs-integration-service")
                        .messages(ReportNoShow.class)
                        .example(new ReportNoShow("CMD-4", "MRU01", "12E45", "front office")),
                TopicSpec.topic("mapping-commands")
                        .describedAs("What the mapping is asked without waiting: an equivalence defined, an agent's "
                                + "proposal requested, a cause resolved if still open, a partner's PMS profile recorded.")
                        .ownedBy("mapping-service").keyedBy("hotelCode (\"chain\" for a chain-wide one), causeKey or partnerCode")
                        .producedBy("integrations-service").consumedBy("mapping-service")
                        .messages(MappingCommand.class)
                        .example(new MappingCommand.DefineEquivalence("CMD-5", CodeType.ROOM_TYPE, "MRU01", "STD-KING",
                                        "KNG", "ana"),
                                new MappingCommand.RequestAgentProposal("CMD-6", "MRU01"),
                                new MappingCommand.ResolveCauseIfOpen("CMD-7", "MISSING_MAPPING:MRU01:ROOM_TYPE:STD-KING", "ana"),
                                new MappingCommand.RecordPartnerProfile("CMD-8", "NORDTRAVEL", "16120699", "Agent")),
                TopicSpec.topic("front-office-commands")
                        .describedAs("What the front office is told: a stay written as the PMS has it, a property's "
                                + "catalogue replaced whole, how the PMS took what the reception did (refused, the room, "
                                + "the check-out's invoice, a charge's posting).")
                        .ownedBy("front-office").keyedBy("pmsHotelCode/pmsReservationId, or pmsHotelCode for a catalogue")
                        .producedBy("pms-integration-service", "integrations-service").consumedBy("front-office")
                        .messages(FrontOfficeCommand.class)
                        .example(new FrontOfficeCommand.WriteStay("CMD-9", "XMAR", "123456", "987654", "12E45",
                                        List.of("12E45"), "3", FrontOfficeCommand.PmsStatus.RESERVED,
                                        new FrontOfficeCommand.Person("C-00042", "P-1", "Ana García", "X1234567",
                                                "ana@example.com", "+34600000000"),
                                        List.of(), "KNG", "BAR", "BKF", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 7),
                                        2, null, new BigDecimal("312.40"), "EUR"),
                                new FrontOfficeCommand.ReplaceCatalogue("CMD-10", "XMAR", List.of(
                                        new FrontOfficeCommand.CatalogueEntry(FrontOfficeCommand.CatalogueType.ROOM_TYPE,
                                                "KNG", "King", null),
                                        new FrontOfficeCommand.CatalogueEntry(FrontOfficeCommand.CatalogueType.ROOM,
                                                "101", "Room 101", "KNG"))),
                                new FrontOfficeCommand.RecordReception("CMD-11", "XMAR", "123456", "12E45",
                                        FrontOfficeCommand.ReceptionOperation.CHECK_OUT, false, "Checked out in Opera", "205",
                                        new FrontOfficeCommand.Invoice("OPERA", "XMAR377", LocalDate.of(2026, 11, 7),
                                                new BigDecimal("312.40"), "EUR", "JVBERi0xLjQK")),
                                new FrontOfficeCommand.RecordCharge("CMD-12", "XMAR", "123456", "12E45", "L-7F3A2C1D",
                                        false, false, "Posted to Opera's folio", "88731245")),
                TopicSpec.topic("front-office-events")
                        .describedAs("What happened at a hotel's reception — a guest checked in, checked out, nobody "
                                + "came, a charge put on the stay's folio or taken back, the company billed changed. The PMS is the master of the stay "
                                + "and its folio: the pms-fo integration records each in it through the engine, and a "
                                + "no-show goes on to the CRS for its fee.")
                        .ownedBy("front-office").keyedBy("hotelCode/stayId")
                        .producedBy("front-office").consumedBy("integrations-service", "customer-history")
                        .messages(FrontOfficeEvent.class)
                        .example(new FrontOfficeEvent.GuestCheckedIn("E-4", AT, "MRU01", "12E45", "12E45", "XMAR",
                                        "123456", "205", 2, "ana"),
                                new FrontOfficeEvent.GuestCheckedOut("E-5", AT, "MRU01", "12E45", "12E45", "XMAR",
                                        "123456", "205", "ana"),
                                new FrontOfficeEvent.NoShowReported("E-6", AT, "MRU01", "12E45", "12E45", "XMAR",
                                        "123456", 2, "ana"),
                                new FrontOfficeEvent.ChargePosted("E-7", AT, "MRU01", "12E45", "12E45", "XMAR", "123456",
                                        "L-7F3A2C1D", FrontOfficeEvent.ChargeKind.LATE_CHECK_OUT, null,
                                        "Late check-out (salida 15:00)", new BigDecimal("50.00"), "MUR", "ana"),
                                new FrontOfficeEvent.ChargeVoided("E-8", AT, "MRU01", "12E45", "12E45", "XMAR", "123456",
                                        "L-9B1E4A20", FrontOfficeEvent.ChargeKind.CONSUMPTION, "MB-02", "Minibar",
                                        new BigDecimal("12.50"), "MUR", "ana"),
                                new FrontOfficeEvent.PaymentTaken("E-10", AT, "MRU01", "12E45", "12E45", "XMAR", "123456",
                                        "P-5C2E", "DEPOSIT", "CASH", new BigDecimal("100.00"), "MUR", null, "ana"),
                                new FrontOfficeEvent.PaymentRefunded("E-11", AT, "MRU01", "12E45", "12E45", "XMAR",
                                        "123456", "P-5C2E", "DEPOSIT", "CASH", new BigDecimal("100.00"), "MUR", null, "ana"),
                                new FrontOfficeEvent.CompanyChanged("E-12", AT, "MRU01", "12E45", "12E45", "XMAR",
                                        "123456", "ACME-SL", "Acme Consulting SL", "ana"),
                                new FrontOfficeEvent.StayClosed("E-9", AT, "MRU01", "12E45", "12E45", "XMAR", "123456",
                                        LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 12), 4, "205", "JS-SEA",
                                        "TODO-INCLUIDO",
                                        List.of(new FrontOfficeEvent.StayGuest("C-00042", true),
                                                new FrontOfficeEvent.StayGuest("pax-2", false)),
                                        List.of(new FrontOfficeEvent.ChargeTotal(FrontOfficeEvent.ChargeKind.ADD_ON,
                                                        new BigDecimal("120.00")),
                                                new FrontOfficeEvent.ChargeTotal(FrontOfficeEvent.ChargeKind.LATE_CHECK_OUT,
                                                        new BigDecimal("50.00"))),
                                        new BigDecimal("170.00"), "MUR")),
                TopicSpec.topic("pms-reservations")
                        .describedAs("A reservation written in the PMS — for whoever follows the PMS (the pms-fo integration).")
                        .ownedBy("pms-integration-service").keyedBy("pmsHotelCode/pmsReservationId")
                        .producedBy("pms-integration-service").consumedBy("integrations-service")
                        .messages(PmsReservationChanged.class)
                        .example(new PmsReservationChanged("E-3", "XMAR", "123456", "MRU01", "12E45", null, AT)),
                TopicSpec.topic("notifications")
                        .describedAs("A notification for the people who look after a hotel: a cause opened, a proposal "
                                + "ready, a write retrying too long… Deduplicated by dedupKey.")
                        .ownedBy("communication-service").keyedBy("dedupKey (none from pms-integration's retry watch)")
                        .producedBy("integrations-service", "mapping-service", "customer-mdm-service", "pms-integration-service", "front-office")
                        .consumedBy("communication-service")
                        .messages(NotificationRequested.class)
                        .example(new NotificationRequested("N-1", NotificationType.CAUSE_OPENED, "MRU01",
                                "MISSING_MAPPING:MRU01:ROOM_TYPE:STD-KING", "STD-KING has no equivalent",
                                "ROOM_TYPE STD-KING of hotel MRU01 has no approved equivalent in the PMS",
                                "/mapping/causes", "cause:MISSING_MAPPING:MRU01:ROOM_TYPE:STD-KING", AT)),
                TopicSpec.topic("notification-resolutions")
                        .describedAs("What a notification was about is resolved: its subject's notifications close.")
                        .ownedBy("communication-service").keyedBy("subject (none from pms-integration's retry watch)")
                        .producedBy("integrations-service", "mapping-service", "customer-mdm-service", "pms-integration-service", "front-office")
                        .consumedBy("communication-service")
                        .messages(NotificationResolved.class)
                        .example(new NotificationResolved("MISSING_MAPPING:MRU01:ROOM_TYPE:STD-KING", "ana", AT)),
                TopicSpec.topic("audit")
                        .describedAs("An action someone (or an agent) took through a service's API or console.")
                        .ownedBy("audit-service").keyedBy("actionId")
                        .producedBy("integrations-service", "mapping-service", "front-office", "booking", "registration-rules").consumedBy("audit-service")
                        .messages(AuditedAction.class)
                        .example(new AuditedAction("A-1", AT, "mapping-service", "approve-proposal", "MRU01", "ana",
                                "{\"proposal\":\"P-3\"}", true, "approved")));
    }

    @TestFactory
    Stream<DynamicTest> eachTopicsSchemaIsWhatItsRecordsSay() {
        return topics().stream().map(spec -> DynamicTest.dynamicTest(spec.topic() + " v" + spec.version(),
                () -> SchemaFiles.publish(spec)));
    }
}
