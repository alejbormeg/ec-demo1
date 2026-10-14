package io.mateu.ecdemo1.pmsintegration.worker;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.ecdemo1.integration.model.mapping.Cause;
import io.mateu.ecdemo1.integration.model.process.Outcome;
import io.mateu.ecdemo1.integration.model.process.ProcessVariables;
import io.mateu.ecdemo1.pmsintegration.clients.IntegrationClients;
import io.mateu.ecdemo1.pmsintegration.ohip.OperaFrontDesk;
import io.mateu.ecdemo1.pmsintegration.ohip.OperaReservations;
import io.mateu.ecdemo1.pmsintegration.ohip.PmsRejectedException;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.worker.api.TaskContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The company of a stay — who is billed when neither the agency nor the guest is —, onto Opera (pms-fo):
 * the step of «asignar-empresa» ({@code assign-company}). The desk set it, changed it or took it away in
 * the front office; Opera's reservation gets the company's profile, and what the stay costs routed to it
 * (folio window 2) — or neither.
 *
 * <p>The company is a partner of the ERP, projected to Opera as a Company profile («proyectar-interlocutor»):
 * one not projected yet waits on {@code MISSING_PARTNER}, which its projection resolves. Idempotent: a
 * reservation that already has that company is left as it is. Opera's refusal waits on {@code PMS_REJECTED}.
 */
@Component
@Slf4j
public class CompanyHandlers {

    static final String ASSIGN_COMPANY = "assign-company";

    final OperaFrontDesk desk;
    final OperaReservations reservations;
    final IntegrationClients integration;
    /** The transaction codes of the accommodation and the board, routed to the company (XMAR: 1100, 1137, 1138). */
    final List<String> routingCodes;

    public CompanyHandlers(OperaFrontDesk desk, OperaReservations reservations, IntegrationClients integration,
                           @Value("${ohip.company-routing-codes:1100,1137,1138}") List<String> routingCodes) {
        this.desk = desk;
        this.reservations = reservations;
        this.integration = integration;
        this.routingCodes = routingCodes.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
    }

    /** The input: the stay both ways, and the company — blank when the desk took it away. */
    public record CompanyTask(String definitionId, String processKey, String hotelCode, String locator, String pmsHotelCode,
                              String pmsReservationId, String stayId, String eventId, String origin, String companyCode,
                              String companyName) {

        List<Variable> variables() {
            var variables = new ArrayList<Variable>();
            ReceptionHandlers.add(variables, ProcessVariables.DEFINITION_ID, definitionId);
            ReceptionHandlers.add(variables, ProcessVariables.PROCESS_KEY, processKey);
            ReceptionHandlers.add(variables, ProcessVariables.HOTEL_CODE, hotelCode);
            ReceptionHandlers.add(variables, ProcessVariables.LOCATOR, locator);
            ReceptionHandlers.add(variables, ProcessVariables.PMS_HOTEL_CODE, pmsHotelCode);
            ReceptionHandlers.add(variables, ProcessVariables.PMS_RESERVATION_ID, pmsReservationId);
            ReceptionHandlers.add(variables, ProcessVariables.STAY_ID, stayId);
            ReceptionHandlers.add(variables, ProcessVariables.EVENT_ID, eventId);
            ReceptionHandlers.add(variables, ProcessVariables.ORIGIN, origin);
            ReceptionHandlers.add(variables, ProcessVariables.COMPANY_CODE, companyCode);
            ReceptionHandlers.add(variables, ProcessVariables.COMPANY_NAME, companyName);
            return variables;
        }

        boolean fromTheCrs() {
            return !pmsHotelCode.equals(hotelCode);
        }

        boolean none() {
            return companyCode == null || companyCode.isBlank();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompanyAssigned(String companyOutcome, String pmsReservationId) {
    }

    /** The company on Opera's reservation — or none —, unless it already is as the desk left it. */
    public CompanyAssigned assignCompany(CompanyTask input, TaskContext task) {
        var found = reservation(input, task);
        if (found.isEmpty()) {
            return new CompanyAssigned(Outcome.WAIT.name(), null);
        }
        var r = found.get();
        var hotel = input.pmsHotelCode();
        var id = OperaReservations.id(r);
        TaskHandlers.tag("opera.hotel", hotel);
        TaskHandlers.tag("opera.reservation.id", id);
        TaskHandlers.tag("frontoffice.company", input.none() ? "none" : input.companyCode());
        var current = OperaFrontDesk.companyProfileId(r).orElse(null);
        try {
            if (input.none()) {
                if (current == null) {
                    TaskHandlers.tag("opera.action", "no-company-kept");
                    return new CompanyAssigned(Outcome.STALE.name(), id);
                }
                desk.detachCompany(hotel, id, current);
                log.info("{}/{}: the company (profile {}) taken off the reservation ({})", hotel, id, current, input.stayId());
                TaskHandlers.tag("opera.action", "company-detached");
                return new CompanyAssigned(Outcome.DONE.name(), id);
            }
            var profile = integration.partnerProfile(input.companyCode()).orElse(null);
            if (profile == null) {
                await(input, List.of(Cause.missingPartner(input.companyCode())));
                return new CompanyAssigned(Outcome.WAIT.name(), id);
            }
            if (profile.pmsProfileId().equals(current)) {
                log.info("{}/{}: the reservation has company {} already", hotel, id, input.companyCode());
                TaskHandlers.tag("opera.action", "company-kept");
                return new CompanyAssigned(Outcome.STALE.name(), id);
            }
            if (current != null) {
                desk.detachCompany(hotel, id, current);
            }
            desk.attachCompany(hotel, id, profile.pmsProfileId(), date(r.path("roomStay").path("arrivalDate")),
                    date(r.path("roomStay").path("departureDate")), routingCodes);
            log.info("{}/{}: company {} (profile {}) on the reservation, the stay routed to it ({})", hotel, id,
                    input.companyCode(), profile.pmsProfileId(), input.stayId());
            TaskHandlers.tag("opera.action", "company-attached");
            return new CompanyAssigned(Outcome.DONE.name(), id);
        } catch (PmsRejectedException e) {
            log.warn("Opera refused {} of {}/{} ({}): {} ({})", task.stepId(), hotel, id, input.stayId(), e.getMessage(),
                    e.errorCode());
            TaskHandlers.tag("opera.refused", e.getMessage());
            await(input, List.of(Cause.pmsRejectedReservation(input.hotelCode(), input.locator(), task.stepId(), e.reason())));
            return new CompanyAssigned(Outcome.WAIT.name(), id);
        }
    }

    Optional<JsonNode> reservation(CompanyTask input, TaskContext task) {
        TaskHandlers.required(task, ProcessVariables.PROCESS_KEY, input.processKey());
        TaskHandlers.required(task, ProcessVariables.DEFINITION_ID, input.definitionId());
        var hotel = TaskHandlers.required(task, ProcessVariables.PMS_HOTEL_CODE, input.pmsHotelCode());
        TaskHandlers.required(task, ProcessVariables.HOTEL_CODE, input.hotelCode());
        TaskHandlers.required(task, ProcessVariables.LOCATOR, input.locator());
        Optional<JsonNode> found;
        if (input.pmsReservationId() != null && !input.pmsReservationId().isBlank()) {
            found = desk.reservation(hotel, input.pmsReservationId());
        } else if (input.fromTheCrs()) {
            found = reservations.byLocator(hotel, input.locator());
        } else {
            found = desk.reservation(hotel, input.locator());
        }
        if (found.isEmpty()) {
            await(input, List.of(Cause.notYetProjected(input.hotelCode(), input.locator())));
        }
        return found;
    }

    static java.time.LocalDate date(JsonNode node) {
        try {
            return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null
                    : java.time.LocalDate.parse(node.asText().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    void await(CompanyTask input, List<Cause> causes) {
        TaskHandlers.tag("mapping.causes", String.join("; ", causes.stream().map(c -> c.key() + "=" + c.description()).toList()));
        integration.await(input.processKey(), input.definitionId(), input.hotelCode(), input.locator(), input.variables(), causes);
    }
}
