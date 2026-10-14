package io.mateu.ecdemo1.pmsintegration.worker;

import io.mateu.ecdemo1.integration.model.customer.IdentityRequest;
import io.mateu.ecdemo1.integration.model.customer.ResolvedIdentity;
import io.mateu.ecdemo1.integration.model.mapping.Cause;
import io.mateu.ecdemo1.integration.model.partner.Partner;
import io.mateu.ecdemo1.pmsintegration.config.OhipProperties;
import io.mateu.ecdemo1.pmsintegration.config.PmsIntegrationProperties;
import io.mateu.ecdemo1.integration.model.mapping.CodeType;
import io.mateu.ecdemo1.integration.model.process.Outcome;
import io.mateu.ecdemo1.integration.model.process.ProcessVariables;
import io.mateu.ecdemo1.integration.model.reservation.Person;
import io.mateu.ecdemo1.integration.model.reservation.Reservation;
import io.mateu.ecdemo1.pmsintegration.clients.IntegrationClients;
import io.mateu.ecdemo1.pmsintegration.clients.IntegrationClients.CodeRef;
import io.mateu.ecdemo1.integration.model.integration.IntegrationStatus;
import io.mateu.ecdemo1.integration.model.integration.IntegrationView;
import io.mateu.ecdemo1.pmsintegration.connections.Connections;
import io.mateu.ecdemo1.pmsintegration.ohip.PmsTransientException;
import io.mateu.ecdemo1.pmsintegration.ohip.OperaProfiles;
import io.mateu.ecdemo1.pmsintegration.ohip.OperaReservations;
import io.mateu.ecdemo1.pmsintegration.ohip.PmsRejectedException;
import io.mateu.ecdemo1.pmsintegration.write.ReservationPayload;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.worker.api.TaskContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The steps that write to Opera, one per task contract (ec-definitions, definitions/tasks; registered
 * in {@link PmsTasks}). Each reads what it acts on again — the process carries
 * references only — and each is idempotent: it looks before it creates, and it writes state, not
 * increments, so running it twice leaves Opera as running it once.
 *
 * <p>Failures follow the HLA's policy. A transient one propagates, and the engine retries the step
 * with backoff, for as long as it takes. A refusal becomes a cause the process waits on; so does a
 * code that stopped having an equivalence since the reservation was prepared.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskHandlers {

    final IntegrationClients integration;
    final OperaProfiles profiles;
    final OperaReservations reservations;
    final ReservationPayload payload;
    final Connections connections;
    final PmsIntegrationProperties settings;
    final OhipProperties ohipProperties;
    final io.mateu.ecdemo1.pmsintegration.frontoffice.StayProjection stays;
    final io.mateu.ecdemo1.pmsintegration.frontoffice.PmsEvents events;

    /**
     * The input of the reservation steps ({@code ensure-guest-profile}, {@code upsert-reservation},
     * {@code cancel-reservation}): the reservation, and what a successor is started with should the
     * step have to wait (the mapping keeps only its relaunch variables). A task carries every variable
     * of its process; the rest are not its.
     */
    public record ReservationTask(String definitionId, String processKey, String hotelCode, String locator,
                                  String version, String eventId, String origin, String guestProfileId) {

        List<Variable> variables() {
            return relaunch(definitionId, processKey, hotelCode, locator, null, version, eventId, origin);
        }
    }

    /** {@code ensure-partner-profile@1}'s input. */
    public record PartnerTask(String definitionId, String processKey, String partnerCode, String version,
                              String eventId, String origin) {

        List<Variable> variables() {
            return relaunch(definitionId, processKey, null, null, partnerCode, version, eventId, origin);
        }
    }

    /** {@code project-stay@1}'s input: the Opera reservation, by property and id. */
    public record StayTask(String pmsHotelCode, String pmsReservationId) {
    }

    /** {@code ensure-guest-profile@1}'s output. What is not known is not written. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GuestProfile(String profileOutcome, String guestProfileId, String customerId) {
    }

    /** {@code upsert-reservation@1}'s and {@code cancel-reservation@1}'s output. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Write(String writeOutcome, String pmsReservationId) {
    }

    /** {@code ensure-partner-profile@1}'s output. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartnerProfiled(String profileOutcome, String pmsProfileIds, String pmsProfileType) {
    }

    public GuestProfile ensureGuestProfile(ReservationTask input, TaskContext task) {
        var r = reservation(task, input);
        var hotel = resolveHotel(task, input, r);
        if (hotel == null) {
            return new GuestProfile(Outcome.WAIT.name(), null, null);
        }
        var customerId = holderCustomer(r);
        try {
            var existing = reservations.byLocator(hotel, r.locator());
            if (existing.isPresent() && !rewritesTheGuest(input)
                    && (reservations.writtenVersion(existing.get()) >= r.version() || OperaReservations.cancelled(existing.get()))) {
                // Opera already holds this version: the reservation will not be written, and neither is
                // its guest. A merge in the MDM is the exception — it projects the same version again
                // precisely to put the new customer code on the profile.
                var id = OperaReservations.guestProfileId(existing.get());
                if (id.isPresent()) {
                    log.info("{} v{}: Opera already holds it; guest profile {} left as it is", r.locator(), r.version(), id.get());
                    tag("opera.profile.id", id.get());
                    tag("opera.profile.action", "kept");
                    return new GuestProfile(Outcome.OK.name(), id.get(), customerId);
                }
            }
            var known = ohipProperties.profileReferences() ? null
                    : existing.flatMap(OperaReservations::guestProfileId).orElse(null);
            // The guest as Salesforce — the master — has them, through the MDM; the reservation's data
            // for what the MDM does not know, or if it does not answer.
            var holder = customerId == null ? r.holder() : integration.customer(customerId)
                    .map(master -> overlay(master, r.holder())).orElse(r.holder());
            var ensured = profiles.ensureGuest(hotel, r.locator(), holder, customerId, known);
            if (customerId != null) {
                integration.xref(customerId, "OPERA", ensured.profileId(), hotel + "/" + r.locator());
            }
            log.info("Guest profile {} {} for {} (customer {})", ensured.profileId(), ensured.created() ? "created" : "updated",
                    r.locator(), customerId);
            tag("opera.profile.id", ensured.profileId());
            tag("opera.profile.action", ensured.created() ? "created" : "updated");
            tag("mdm.customer.id", customerId);
            return new GuestProfile(Outcome.OK.name(), ensured.profileId(), customerId);
        } catch (PmsRejectedException e) {
            rejected(task, input, r, "guest profile of " + r.locator(), e);
            return new GuestProfile(Outcome.WAIT.name(), null, null);
        }
    }

    /**
     * Who the passengers are, asked of the customer MDM — the holder first, then each room's guests —
     * and the holder's customer code. The MDM is not a gate (HLA CRM-MDM, «no bloquear la venta»):
     * if it does not answer, the profile goes without the code, and the next projection of the
     * reservation stamps it.
     */
    String holderCustomer(Reservation r) {
        var passengers = new ArrayList<Person>();
        if (r.holder() != null) {
            passengers.add(r.holder());
        }
        r.rooms().forEach(room -> {
            if (room.guests() != null) {
                passengers.addAll(room.guests());
            }
        });
        if (passengers.isEmpty()) {
            return null;
        }
        try {
            var identities = integration.identities(new IdentityRequest(r.hotelCode(), r.locator(), passengers));
            return identities.stream().filter(i -> i.passenger() == 0).map(ResolvedIdentity::customerId).findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.warn("Customer MDM unavailable for {}: the guest profile goes without its customer code ({})", r.locator(), e.getMessage());
            return null;
        }
    }

    /**
     * The HLA's «Grabar la reserva»: find it by the CRS locator, compare the CRS version its UDF
     * carries with the one being written, and create or update — or, if Opera already holds this
     * version or a newer one, write nothing. OHIP has no conditional write to make the read-compare-write
     * atomic: the definition serialises it per reservation with the engine's lock (LOCK / UNLOCK
     * {@code reservation}, {@code hotelCode/locator}, shared with the cancellation), and the version
     * guard keeps an older version from overwriting a newer one whatever order they arrive in.
     */
    public Write upsertReservation(ReservationTask input, TaskContext task) {
        var r = reservation(task, input);
        var codes = codesOf(r);
        var resolved = integration.resolve(r.hotelCode(), new ArrayList<>(codes));
        var partner = r.partnerCode() == null ? null : integration.partner(r.partnerCode());
        var partnerProfile = r.partnerCode() == null ? null : integration.partnerProfile(r.partnerCode()).orElse(null);
        var missing = new ArrayList<>(resolved.missing());
        if (partner != null && partnerProfile == null) {
            missing.add(Cause.missingPartner(partner.code()));
        }
        if (!missing.isEmpty()) {
            // Approved when the reservation was prepared, gone since: wait again, as preparing would.
            await(input, r, missing);
            return new Write(Outcome.WAIT.name(), null);
        }
        var hotel = resolved.target(CodeType.HOTEL, r.hotelCode());
        try {
            var body = payload.build(r, resolved, hotel, required(task, ProcessVariables.GUEST_PROFILE_ID, input.guestProfileId()), partner,
                    partnerProfile == null ? null : partnerProfile.pmsProfileId(),
                    partnerProfile == null ? null : partnerProfile.profileType());
            var existing = reservations.byLocator(hotel, r.locator());
            String reservationId;
            if (existing.isEmpty()) {
                reservationId = reservations.create(hotel, body);
                log.info("{} v{} created in {} as {}", r.locator(), r.version(), hotel, reservationId);
                written(hotel, reservationId, "created", r.version());
            } else {
                reservationId = OperaReservations.id(existing.get());
                var written = reservations.writtenVersion(existing.get());
                if (written >= r.version() || OperaReservations.cancelled(existing.get())) {
                    log.info("{} v{}: Opera already holds v{}{}, nothing to write", r.locator(), r.version(), written,
                            OperaReservations.cancelled(existing.get()) ? " (cancelled)" : "");
                    supersedeRefusals(r, written);
                    written(hotel, reservationId, "stale", written);
                    // Opera had it already; whoever consumes Opera is told all the same — a merge in the MDM,
                    // a backfill, projects the same version precisely to be read again.
                    events.written(hotel, reservationId, r.hotelCode(), r.locator(), task.workflowDefinitionId());
                    return new Write(Outcome.STALE.name(), reservationId);
                }
                reservations.update(hotel, reservationId, body);
                log.info("{} v{} -> v{} updated in {} ({})", r.locator(), written, r.version(), hotel, reservationId);
                written(hotel, reservationId, "updated", r.version());
            }
            if (!ohipProperties.postDeposits() && !r.payments().isEmpty()) {
                log.info("{}: {} payment(s) not posted to the folio — deposits are off for this tenant", r.locator(), r.payments().size());
            }
            for (var payment : ohipProperties.postDeposits() ? r.payments() : List.<io.mateu.ecdemo1.integration.model.reservation.Payment>of()) {
                reservations.ensureDeposit(hotel, reservationId, payment,
                        resolved.target(CodeType.PAYMENT_METHOD, payment.methodCode()), r.currency());
            }
            supersedeRefusals(r, r.version());
            events.written(hotel, reservationId, r.hotelCode(), r.locator(), task.workflowDefinitionId());
            return new Write(Outcome.DONE.name(), reservationId);
        } catch (PmsRejectedException e) {
            rejected(task, input, r, "reservation " + r.locator(), e);
            return new Write(Outcome.WAIT.name(), null);
        }
    }

    /**
     * What was written to Opera, on the task's span — for the booking's journey (journey-service),
     * which shows the Opera reservation, what was done to it and the CRS version it holds.
     */
    static void written(String hotel, String reservationId, String action, long version) {
        tag("opera.hotel", hotel);
        tag("opera.reservation.id", reservationId);
        tag("opera.action", action);
        tag("opera.version", String.valueOf(version));
    }

    static void tag(String key, String value) {
        if (value != null) {
            io.opentelemetry.api.trace.Span.current().setAttribute(key, value);
        }
    }

    /** The steps whose refusal a newer version written to Opera leaves with nothing to wait for. */
    static final List<String> SUPERSEDED_STEPS = List.of("ensure-guest-profile", "upsert-reservation");

    /**
     * Opera holds this reservation at {@code version} now. An older version Opera refused — a room type
     * with no rooms left, say — waits on its refusal; once a newer version is in, there is nothing left
     * for anyone to resolve: the refusal is resolved here, the process that waited on it resumes, finds
     * Opera ahead of it and ends. A cause that is not open is left alone; if the mapping does not
     * answer, the refusal stays for a person, and the write is not undone for it.
     */
    void supersedeRefusals(Reservation r, long version) {
        for (var step : SUPERSEDED_STEPS) {
            try {
                integration.resolveCauseIfOpen(Cause.pmsRejectedReservation(r.hotelCode(), r.locator(), step, "").key(),
                        "pms-integration: v%d is in Opera".formatted(version));
            } catch (RuntimeException e) {
                log.warn("{}: a refusal of its {} could not be resolved as superseded: {}", r.locator(), step, e.getMessage());
            }
        }
    }

    /**
     * Each night's base as the amount itself. On a modification OPERA re-rates the night from its own
     * rate plan and keeps the amount sent only as a share of that base — a 25% fee of the CRS's price
     * came out as 25% of Opera's. With the base given, the night costs what is sent.
     */
    static com.fasterxml.jackson.databind.JsonNode withBaseAmounts(com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var base = object.get("base");
            if (base instanceof com.fasterxml.jackson.databind.node.ObjectNode b && b.has("amountBeforeTax")) {
                b.set("baseAmount", b.get("amountBeforeTax"));
            }
            object.forEach(TaskHandlers::withBaseAmounts);
        } else if (node != null && node.isArray()) {
            node.forEach(TaskHandlers::withBaseAmounts);
        }
        return node;
    }

    /** The codes a reservation is written to Opera with: hotel, channel, each room's, each payment's. */
    static LinkedHashSet<CodeRef> codesOf(Reservation r) {
        var codes = new LinkedHashSet<CodeRef>();
        codes.add(new CodeRef(CodeType.HOTEL, r.hotelCode()));
        codes.add(new CodeRef(CodeType.CHANNEL, r.channelCode()));
        r.rooms().forEach(room -> {
            codes.add(new CodeRef(CodeType.ROOM_TYPE, room.roomTypeCode()));
            codes.add(new CodeRef(CodeType.RATE_PLAN, room.ratePlanCode()));
            codes.add(new CodeRef(CodeType.BOARD, room.boardCode()));
        });
        r.payments().forEach(p -> codes.add(new CodeRef(CodeType.PAYMENT_METHOD, p.methodCode())));
        return codes;
    }

    /**
     * The reservation as it costs its no-show fee: every night's rate by the same share, the last one
     * taking the rounding, so that the stay adds up to the fee exactly.
     */
    public static Reservation withFee(Reservation r) {
        var fee = r.cancellationFee();
        var original = r.originalAmount() == null || r.originalAmount().signum() == 0 ? null : r.originalAmount();
        if (fee == null || original == null) {
            return r;
        }
        var share = fee.divide(original, 10, java.math.RoundingMode.HALF_UP);
        var rooms = new ArrayList<io.mateu.ecdemo1.integration.model.reservation.Room>();
        var written = java.math.BigDecimal.ZERO;
        io.mateu.ecdemo1.integration.model.reservation.NightlyRate last = null;
        int lastRoom = -1, lastNight = -1;
        for (var room : r.rooms()) {
            var nights = new ArrayList<io.mateu.ecdemo1.integration.model.reservation.NightlyRate>();
            for (var night : room.nightlyRates()) {
                var amount = night.amount().multiply(share).setScale(2, java.math.RoundingMode.HALF_UP);
                written = written.add(amount);
                nights.add(new io.mateu.ecdemo1.integration.model.reservation.NightlyRate(night.date(), amount));
            }
            rooms.add(new io.mateu.ecdemo1.integration.model.reservation.Room(room.line(), room.roomTypeCode(),
                    room.ratePlanCode(), room.boardCode(), room.adults(), room.childrenAges(), room.guests(), nights));
            if (!nights.isEmpty()) {
                lastRoom = rooms.size() - 1;
                lastNight = nights.size() - 1;
                last = nights.get(lastNight);
            }
        }
        if (last != null && written.compareTo(fee) != 0) {
            var room = rooms.get(lastRoom);
            var nights = new ArrayList<>(room.nightlyRates());
            nights.set(lastNight, new io.mateu.ecdemo1.integration.model.reservation.NightlyRate(last.date(),
                    last.amount().add(fee.subtract(written))));
            rooms.set(lastRoom, new io.mateu.ecdemo1.integration.model.reservation.Room(room.line(), room.roomTypeCode(),
                    room.ratePlanCode(), room.boardCode(), room.adults(), room.childrenAges(), room.guests(), nights));
        }
        return new Reservation(r.hotelCode(), r.locator(), r.version(), r.status(), r.channelCode(), r.partnerCode(),
                r.externalReference(), r.arrival(), r.departure(), r.currency(), r.holder(), rooms, r.payments(), fee,
                r.comments(), r.cancellationReasonCode(), r.cancellationFee(), r.originalAmount());
    }

    /**
     * Cancels in Opera. A reservation Opera does not have yet is not skipped: the cancellation waits
     * for it to be projected, so the PMS keeps the record and a penalty would have a folio (R37).
     */
    public Write cancelReservation(ReservationTask input, TaskContext task) {
        var r = reservation(task, input);
        var codes = new ArrayList<CodeRef>();
        codes.add(new CodeRef(CodeType.HOTEL, r.hotelCode()));
        if (r.cancellationReasonCode() != null) {
            codes.add(new CodeRef(CodeType.CANCELLATION_REASON, r.cancellationReasonCode()));
        }
        var resolved = integration.resolve(r.hotelCode(), codes);
        if (!resolved.missing().isEmpty()) {
            await(input, r, resolved.missing());
            return new Write(Outcome.WAIT.name(), null);
        }
        var hotel = resolved.target(CodeType.HOTEL, r.hotelCode());
        var existing = reservations.byLocator(hotel, r.locator());
        if (existing.isEmpty()) {
            await(input, r, List.of(Cause.notYetProjected(r.hotelCode(), r.locator())));
            return new Write(Outcome.WAIT.name(), null);
        }
        var reservationId = OperaReservations.id(existing.get());
        if (OperaReservations.cancelled(existing.get())) {
            events.written(hotel, reservationId, r.hotelCode(), r.locator(), task.workflowDefinitionId());
            return new Write(Outcome.STALE.name(), reservationId);
        }
        try {
            if (r.noShow() && r.cancellationFee() != null) {
                // A no-show still costs its fee: Opera's reservation says so before it is cancelled — once
                // cancelled, Opera takes no more changes.
                var all = new ArrayList<>(codesOf(r));
                all.add(new CodeRef(CodeType.CANCELLATION_REASON, r.cancellationReasonCode()));
                var full = integration.resolve(r.hotelCode(), all);
                if (!full.missing().isEmpty()) {
                    await(input, r, full.missing());
                    return new Write(Outcome.WAIT.name(), null);
                }
                var partner = r.partnerCode() == null ? null : integration.partner(r.partnerCode());
                var partnerProfile = r.partnerCode() == null ? null : integration.partnerProfile(r.partnerCode()).orElse(null);
                var charged = withFee(r);
                var body = payload.build(charged, full, hotel,
                        OperaReservations.guestProfileId(existing.get()).orElse(null), partner,
                        partnerProfile == null ? null : partnerProfile.pmsProfileId(),
                        partnerProfile == null ? null : partnerProfile.profileType());
                reservations.update(hotel, reservationId, withBaseAmounts(body));
                log.info("{}: a no-show — its fee of {} (of {}) written to {} before cancelling", r.locator(),
                        r.cancellationFee(), r.originalAmount(), reservationId);
            }
            var reason = r.cancellationReasonCode() == null ? null
                    : resolved.target(CodeType.CANCELLATION_REASON, r.cancellationReasonCode());
            reservations.cancel(hotel, reservationId, reason, r.cancellationReasonCode() == null
                    ? "Cancelled in the CRS"
                    : r.noShow() ? "No show: cancelled in the CRS with a fee of %s %s (of %s)".formatted(
                            r.cancellationFee(), r.currency(), r.originalAmount())
                    : "Cancelled in the CRS (reason " + r.cancellationReasonCode() + ")");
            log.info("{} cancelled in {} ({})", r.locator(), hotel, reservationId);
            written(hotel, reservationId, r.noShow() ? "no-show" : "cancelled", r.version());
            events.written(hotel, reservationId, r.hotelCode(), r.locator(), task.workflowDefinitionId());
            return new Write(Outcome.DONE.name(), reservationId);
        } catch (PmsRejectedException e) {
            rejected(task, input, r, "cancellation of " + r.locator(), e);
            return new Write(Outcome.WAIT.name(), null);
        }
    }

    /**
     * The partner as a profile in Opera — «Proyectar Interlocutor», from the ERP to Opera. What the ERP
     * already records as its Opera profile is used as it is: nothing is written. Otherwise Opera is
     * asked first, by the partner's CorporateId, in case it has it and the ERP does not know; and only
     * if it does not, the profile is created. Whichever it is, the next step writes it back to the ERP,
     * so it is never created twice.
     */
    public PartnerProfiled ensurePartnerProfile(PartnerTask input, TaskContext task) {
        var partner = integration.partner(required(task, ProcessVariables.PARTNER_CODE, input.partnerCode()));
        if (partner.pmsProfileId() != null && !partner.pmsProfileId().isBlank()) {
            // the profile the ERP records, brought up to a change of the partner (its data edited at the
            // ERP or the front office); as it is when it carries this version already
            try {
                if (partner.pmsProfileType() != null && profiles.updatePartner(anyHotel(), partner.pmsProfileId(), partner,
                        partner.pmsProfileType())) {
                    log.info("Partner {} v{}: its profile {} in Opera brought up to date", partner.code(), partner.version(),
                            partner.pmsProfileId());
                    return profiled(Outcome.OK, partner.pmsProfileId(), partner.pmsProfileType());
                }
            } catch (PmsRejectedException e) {
                integration.await(required(task, ProcessVariables.PROCESS_KEY, input.processKey()),
                        required(task, ProcessVariables.DEFINITION_ID, input.definitionId()), null,
                        partner.code(), input.variables(), List.of(Cause.pmsRejectedPartner(partner.code(), e.reason())));
                return new PartnerProfiled(Outcome.WAIT.name(), null, null);
            }
            log.info("Partner {} is already profile {} in Opera, as the ERP records", partner.code(), partner.pmsProfileId());
            return profiled(Outcome.STALE, partner.pmsProfileId(), partner.pmsProfileType());
        }
        var resolved = integration.resolve(null, List.of(new CodeRef(CodeType.PARTNER_TYPE, partner.type().name())));
        if (!resolved.missing().isEmpty()) {
            integration.await(required(task, ProcessVariables.PROCESS_KEY, input.processKey()),
                    required(task, ProcessVariables.DEFINITION_ID, input.definitionId()), null,
                    partner.code(), input.variables(), resolved.missing());
            return new PartnerProfiled(Outcome.WAIT.name(), null, null);
        }
        var profileType = resolved.target(CodeType.PARTNER_TYPE, partner.type().name());
        var hotel = anyHotel();
        try {
            if (!ohipProperties.profileReferences()) {
                var found = profiles.byCorporateId(hotel, partner.code(), profileType);
                if (found.isPresent()) {
                    log.info("Partner {} found in Opera by its CorporateId: profile {}", partner.code(), found.get());
                    return profiled(Outcome.STALE, found.get(), profileType);
                }
            } else {
                var current = profiles.byExternalId(hotel, "PARTNER-" + partner.code());
                if (current.isPresent() && profiles.projectedVersion(current.get()) >= partner.version()) {
                    return profiled(Outcome.STALE, current.get().path("profileIdList").path(0).path("id").asText(), profileType);
                }
            }
            var ensured = profiles.ensurePartner(hotel, partner, profileType);
            log.info("Partner {} v{} is profile {} ({}){}", partner.code(), partner.version(), ensured.profileId(), profileType,
                    ensured.created() ? ", created in Opera" : "");
            return profiled(Outcome.OK, ensured.profileId(), profileType);
        } catch (PmsRejectedException e) {
            integration.await(required(task, ProcessVariables.PROCESS_KEY, input.processKey()),
                    required(task, ProcessVariables.DEFINITION_ID, input.definitionId()), null,
                    partner.code(), input.variables(), List.of(Cause.pmsRejectedPartner(partner.code(), e.reason())));
            return new PartnerProfiled(Outcome.WAIT.name(), null, null);
        }
    }

    static PartnerProfiled profiled(Outcome outcome, String profileId, String profileType) {
        return new PartnerProfiled(outcome.name(), profileId, profileType == null ? "" : profileType);
    }

    /** «Proyectar estancia»: the Opera reservation, as Opera holds it now, into the front office. */
    public Void projectStay(StayTask input, TaskContext task) {
        stays.project(required(task, ProcessVariables.PMS_HOTEL_CODE, input.pmsHotelCode()),
                required(task, ProcessVariables.PMS_RESERVATION_ID, input.pmsReservationId()));
        return null;
    }

    /**
     * The hotel a chain-level call is made "from". OHIP demands a hotel header even on the profiles
     * API, where the profile belongs to no hotel. Any property whose connection has been verified:
     * profiles are the chain's, and every hotel of the chain lives in the same Opera environment (R24).
     * None yet is transient — the partner is projected once the first integration is past its
     * connectivity check.
     */
    String anyHotel() {
        return connections.integrations().stream()
                .filter(i -> !Set.of(IntegrationStatus.CREATED, IntegrationStatus.CONNECTIVITY_FAILED,
                        IntegrationStatus.DECOMMISSIONED).contains(i.status()))
                .map(IntegrationView::pmsHotelCode)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new PmsTransientException("No integration with a verified connection to Opera yet"));
    }

    /** The PMS hotel of the reservation, or null having registered that its equivalence is missing. */
    String resolveHotel(TaskContext task, ReservationTask input, Reservation r) {
        var resolved = integration.resolve(r.hotelCode(), List.of(new CodeRef(CodeType.HOTEL, r.hotelCode())));
        if (!resolved.missing().isEmpty()) {
            await(input, r, resolved.missing());
            return null;
        }
        return resolved.target(CodeType.HOTEL, r.hotelCode());
    }

    /** Opera refused: the process waits on the refusal, a cause a person resolves (or a newer version supersedes). */
    void rejected(TaskContext task, ReservationTask input, Reservation r, String what, PmsRejectedException e) {
        log.warn("Opera refused the {}: {} ({})", what, e.getMessage(), e.errorCode());
        await(input, r, List.of(Cause.pmsRejectedReservation(r.hotelCode(), r.locator(), task.stepId(),
                e.reason())));
    }

    void await(ReservationTask input, Reservation r, List<Cause> causes) {
        tag("mapping.causes", String.join("; ", causes.stream().map(c -> c.key() + "=" + c.description()).toList()));
        integration.await(input.processKey(), input.definitionId(), r.hotelCode(), r.locator(), input.variables(), causes);
    }

    Reservation reservation(TaskContext task, ReservationTask input) {
        required(task, ProcessVariables.PROCESS_KEY, input.processKey());
        required(task, ProcessVariables.DEFINITION_ID, input.definitionId());
        return integration.reservation(required(task, ProcessVariables.HOTEL_CODE, input.hotelCode()),
                required(task, ProcessVariables.LOCATOR, input.locator()));
    }

    /** Whether this projection exists to rewrite the guest: a merge or a change of the customer in the MDM. */
    static boolean rewritesTheGuest(ReservationTask input) {
        return input.origin() != null && input.origin().startsWith("mdm-");
    }

    /** The master's data, and the reservation's where the master has none. */
    public static Person overlay(Person master, Person reservation) {
        if (reservation == null) {
            return master;
        }
        return new Person(or(master.firstName(), reservation.firstName()), or(master.lastName(), reservation.lastName()),
                reservation.type(), reservation.age(), or(master.email(), reservation.email()), or(master.phone(), reservation.phone()),
                or(master.nationality(), reservation.nationality()),
                master.birthDate() != null ? master.birthDate() : reservation.birthDate(),
                or(master.documentType(), reservation.documentType()), or(master.documentNumber(), reservation.documentNumber()));
    }

    static String or(String value, String otherwise) {
        return value == null || value.isBlank() ? otherwise : value;
    }

    static String required(TaskContext task, String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Step %s needs the variable %s".formatted(task.stepId(), name));
        }
        return value;
    }

    /** What a successor is started with, as the process has it: the mapping keeps its relaunch variables. */
    static List<Variable> relaunch(String definitionId, String processKey, String hotelCode, String locator,
                                   String partnerCode, String version, String eventId, String origin) {
        var variables = new ArrayList<Variable>();
        add(variables, ProcessVariables.DEFINITION_ID, definitionId);
        add(variables, ProcessVariables.PROCESS_KEY, processKey);
        add(variables, ProcessVariables.HOTEL_CODE, hotelCode);
        add(variables, ProcessVariables.LOCATOR, locator);
        add(variables, ProcessVariables.PARTNER_CODE, partnerCode);
        add(variables, ProcessVariables.VERSION, version);
        add(variables, ProcessVariables.EVENT_ID, eventId);
        add(variables, ProcessVariables.ORIGIN, origin);
        return variables;
    }

    static void add(List<Variable> variables, String name, String value) {
        if (value != null) {
            variables.add(new Variable(name, value));
        }
    }
}
