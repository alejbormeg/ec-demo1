package io.mateu.ecdemo1.pmsintegration.frontoffice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand;
import io.mateu.ecdemo1.pmsintegration.clients.IntegrationClients;
import io.mateu.ecdemo1.pmsintegration.config.OhipProperties;
import io.mateu.ecdemo1.pmsintegration.config.PmsIntegrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.HashSet;

/**
 * «Proyectar estancia» (pms-fo): an Opera reservation, as Opera holds it now, into the hotel's front
 * office. Read again from Opera — the process carries only its id — mapped to the front office's
 * model ({@link StayMapper}), and sent to the front office as a command on its topic. The front office
 * takes it once and orders it by Opera's last modification: projecting the same reservation twice,
 * or late, leaves the stay as the latest.
 *
 * <p>Who the holder is comes from the chain's MDM — which customer Opera's guest profile is (the
 * cross reference {@code ensure-guest-profile} recorded) and that customer's golden record over
 * Opera's data. One born in Opera the MDM does not know goes as Opera has it. The MDM not answering
 * does not stop the stay: it goes without the customer, and the next projection names it.
 *
 * <p>Transient failures — Opera not answering — propagate, and the engine retries the step.
 */
@Component
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
@Slf4j
public class StayProjection {

    /** The output binding of {@link FrontOfficeCommand#TOPIC}. */
    public static final String BINDING = "frontOfficeCommands";

    final OperaStays stays;
    final IntegrationClients integration;
    final OhipProperties ohip;
    final PmsIntegrationProperties properties;
    final StreamBridge streamBridge;
    final ObjectMapper objectMapper;
    /** The run's context in Opera, swapped at runtime by reset-demo. */
    final io.mateu.ecdemo1.pmsintegration.config.OperaContext context;
    /** Which CRS hotel an Opera property is: the CRS's guests are read by the CRS's hotel code. */
    final io.mateu.ecdemo1.pmsintegration.connections.Connections connections;

    public StayProjection(OperaStays stays, IntegrationClients integration, OhipProperties ohip,
                          PmsIntegrationProperties properties, StreamBridge streamBridge, ObjectMapper objectMapper) {
        this(stays, integration, ohip, properties, streamBridge, objectMapper,
                ohip == null ? null : io.mateu.ecdemo1.pmsintegration.config.OperaContext.of(ohip), null);
    }

    public void project(String hotel, String reservationId) {
        var found = stays.byId(hotel, reservationId);
        if (found.isEmpty()) {
            log.info("{}/{}: Opera does not have it; nothing for the front office", hotel, reservationId);
            return;
        }
        var reservation = found.get();
        var profileId = StayMapper.person(firstGuest(reservation)).pmsProfileId();
        var customerId = profileId == null ? null : integration.customerByPmsProfile(profileId).orElse(null);
        var master = customerId == null ? null : integration.customer(customerId)
                .map(p -> new FrontOfficeCommand.Person(customerId, profileId,
                        ((p.firstName() == null ? "" : p.firstName()) + " " + (p.lastName() == null ? "" : p.lastName())).trim(),
                        p.documentNumber(), p.email(), p.phone()))
                .orElse(null);
        var command = StayMapper.toWriteStay(hotel, reservation, new StayMapper.Context(context.externalSystemCode(),
                new HashSet<>(properties.noShowCancellationCodes()), customerId, master));
        command = StayMapper.withCrsGuests(command, crsReservation(hotel, command));
        // the company is a partner of the ERP's: its CorporateId in Opera, or the profile the mapping
        // recorded when it projected the partner
        var company = StayMapper.company(reservation).orElse(null);
        if (company != null) {
            var code = company.corporateId() != null ? company.corporateId()
                    : company.pmsProfileId() == null ? null : integration.partnerOfProfile(company.pmsProfileId()).orElse(null);
            command = StayMapper.withCompanyCode(command, code);
        }
        send(command);
        log.info("{}/{} ({}) to the front office: {} {}..{} {} {} v{}", hotel, reservationId,
                command.crsLocator() == null ? "born in Opera" : "CRS " + command.crsLocator(), command.status(),
                command.checkIn(), command.checkOut(), command.roomTypeCode(), command.boardCode() == null ? "room only" : command.boardCode(),
                command.pmsVersion());
        if (customerId != null) {
            integration.xref(customerId, "FRONT_OFFICE", customerId, hotel + "/" + reservationId);
        }
    }

    /**
     * The CRS's booking behind the stay, when Opera knows fewer of its people than it has: Opera holds
     * the holder's profile only — the integration writes no profile for the others — so their names
     * are the CRS's. Null when it was born in Opera, when Opera already names everyone, or when the CRS
     * does not answer (the stay goes as Opera has it, and the next projection names them).
     */
    io.mateu.ecdemo1.integration.model.reservation.Reservation crsReservation(String hotel, FrontOfficeCommand.WriteStay command) {
        if (command.crsLocator() == null || connections == null
                || (command.companions() == null ? 0 : command.companions().size()) >= command.pax() - 1) {
            return null;
        }
        try {
            var crsHotel = connections.integrations().stream().filter(i -> hotel.equals(i.pmsHotelCode()))
                    .map(io.mateu.ecdemo1.integration.model.integration.IntegrationView::crsHotelCode)
                    .findFirst().orElse(null);
            return crsHotel == null ? null : integration.reservation(crsHotel, command.crsLocator());
        } catch (RuntimeException e) {
            log.info("{}: the CRS's guests of {} not read ({}); the stay goes with Opera's", hotel, command.crsLocator(),
                    e.getMessage());
            return null;
        }
    }

    static com.fasterxml.jackson.databind.JsonNode firstGuest(com.fasterxml.jackson.databind.JsonNode reservation) {
        for (var guest : reservation.path("reservationGuests")) {
            if (guest.path("primary").asBoolean(false)) {
                return guest;
            }
        }
        return reservation.path("reservationGuests").path(0);
    }

    /** On the front office's topic, keyed by the reservation; a broker that does not take it fails the step, which is retried. */
    void send(FrontOfficeCommand command) {
        byte[] payload;
        try {
            payload = objectMapper.writerFor(FrontOfficeCommand.class).writeValueAsBytes(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + command, e);
        }
        var sent = streamBridge.send(BINDING, MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.KEY, command.key())
                .setHeader("contentType", MimeTypeUtils.APPLICATION_JSON_VALUE)
                .build());
        if (!sent) {
            throw new IllegalStateException("The front office's topic did not take " + command.key());
        }
    }
}
