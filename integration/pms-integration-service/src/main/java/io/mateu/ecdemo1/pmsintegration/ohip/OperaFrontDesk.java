package io.mateu.ecdemo1.pmsintegration.ohip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mateu.ecdemo1.pmsintegration.config.OhipProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The reception in Opera (OHIP front desk and cashiering): what the front office did, recorded in the
 * PMS — the master of the stay. Found against OHIP UAT (XMAR, 2026-09-29):
 *
 * <ul>
 *   <li>room: {@code POST /fof/v1/hotels/{h}/reservations/{id}/roomAssignments} ({@code criteria.roomId});
 *       the rooms Opera would give it, {@code GET …/verifyCheckIns} ({@code suggestedRoomNumbers}) —
 *       which also says, read-only, whether it can be checked in (FOF00067 «The guest's arrival is not
 *       scheduled for today» when the arrival is not Opera's business date);</li>
 *   <li>check-in: {@code POST /fof/v1/hotels/{h}/reservations/{id}/checkIns}; a reservation that is
 *       not «due in» — arriving on the business date — is refused;</li>
 *   <li>check-out: {@code POST /csh/v1/hotels/{h}/reservations/{id}/checkOuts}, with the integration's
 *       cashier ({@link OhipProperties#cashierId()}): without one, 400 FOF00094 «Invalid Cashier»; before
 *       the departure date, made an early departure first ({@code PUT …/earlyDeparture}; the business
 *       date, {@code GET /ent/config/v1/hotels/{h}/operaContext}, says which);</li>
 *   <li>the folio settled first with what the desk collected: {@code GET …/folios?fetchInstructions=Windowbalances},
 *       {@code POST /csh/v1/hotels/{h}/reservations/{id}/payments} (with the cashier);</li>
 *   <li>the invoice: the folios the check-out closed, {@code GET /csh/v1/hotels/{h}/folioHistory
 *       ?checkOut=true}; its printable document, {@code POST …/reservations/{id}/folios} (generate,
 *       with the cashier) → {@code storedFolioId} → {@code GET /csh/v1/hotels/{h}/storedFolios/{id}}
 *       → {@code folioReportURL};</li>
 *   <li>a no-show: OHIP has no call that sets Opera's «No Show» (its Night Audit does); the reservation
 *       gets a comment saying who reported it and when;</li>
 *   <li>the desk's charges: {@code POST /csh/v1/hotels/{h}/reservations/{id}/charges} (transaction code,
 *       amount, {@code postingReference}, with the cashier) — a reversal is the same, negative; the
 *       folio's postings, {@code GET …/folios?fetchInstructions=Postings&summaryOnly=false}, found by their reference (padded by Opera with a blank).</li>
 * </ul>
 *
 * <p>Every refusal is a {@link PmsRejectedException}, every «not now» a {@link PmsTransientException}
 * ({@link OhipClient}): the tasks turn the first into a cause, and the engine retries the second.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperaFrontDesk {

    /** What a no-show comment starts with: how the task knows it already wrote it. */
    public static final String NO_SHOW_COMMENT = "No show";

    final OhipClient ohip;
    final OhipProperties properties;
    final ObjectMapper objectMapper;

    /** The reservation, whole, if Opera has it. */
    public Optional<JsonNode> reservation(String hotelId, String reservationId) {
        return ohip.find(hotelId, "/rsv/v1/hotels/{h}/reservations/{id}", hotelId, reservationId)
                .map(body -> body.path("reservations").path("reservation"))
                .filter(list -> list.isArray() && !list.isEmpty())
                .map(list -> list.get(0));
    }

    /** Opera's status of it: Reserved, InHouse, CheckedOut, Cancelled, NoShow… */
    public static String status(JsonNode reservation) {
        return reservation.path("reservationStatus").asText("");
    }

    public static boolean inHouse(JsonNode reservation) {
        return "InHouse".equalsIgnoreCase(status(reservation));
    }

    public static boolean checkedOut(JsonNode reservation) {
        return "CheckedOut".equalsIgnoreCase(status(reservation));
    }

    public static boolean cancelled(JsonNode reservation) {
        var status = status(reservation);
        return "Cancelled".equalsIgnoreCase(status) || "NoShow".equalsIgnoreCase(status);
    }

    /** The room Opera has the reservation in, if any. */
    public static Optional<String> room(JsonNode reservation) {
        var stay = reservation.path("roomStay");
        var room = stay.path("currentRoomInfo").path("roomId").asText("");
        if (room.isBlank()) {
            room = stay.path("roomRates").path(0).path("roomId").asText("");
        }
        if (room.isBlank()) {
            room = stay.path("roomId").asText("");
        }
        return room.isBlank() ? Optional.empty() : Optional.of(room);
    }

    /**
     * The rooms Opera would give the reservation, best first — read-only. Opera answers this only for a
     * reservation it could check in now: otherwise the refusal says why.
     */
    public List<String> suggestedRooms(String hotelId, String reservationId) {
        var answer = ohip.get(hotelId, "/fof/v1/hotels/{h}/reservations/{id}/verifyCheckIns", hotelId, reservationId).body();
        var rooms = new ArrayList<String>();
        for (var r : answer == null ? List.<JsonNode>of() : answer.path("reservation")) {
            for (var n : r.path("roomStay").path("currentRoomInfo").path("suggestedRoomNumbers")) {
                rooms.add(n.asText());
            }
        }
        return rooms;
    }

    public void assignRoom(String hotelId, String reservationId, String roomId) {
        var body = objectMapper.createObjectNode();
        var criteria = body.putObject("criteria");
        criteria.put("hotelId", hotelId);
        criteria.putObject("reservationIdList").put("id", reservationId).put("type", "Reservation");
        criteria.put("roomId", roomId);
        ohip.post(hotelId, "/fof/v1/hotels/{h}/reservations/{id}/roomAssignments", body, hotelId, reservationId);
    }

    public void checkIn(String hotelId, String reservationId, String roomId) {
        var body = objectMapper.createObjectNode();
        var reservation = body.putObject("reservation");
        if (roomId != null) {
            reservation.put("roomId", roomId);
        }
        reservation.put("stopCheckin", false);
        reservation.put("printRegistration", false);
        reservation.put("checkInInitiatedBy", "Integration");
        body.put("includeNotifications", false);
        ohip.post(hotelId, "/fof/v1/hotels/{h}/reservations/{id}/checkIns", body, hotelId, reservationId);
    }

    /** The property's business date — Opera's «today», which need not be the calendar's (XMAR's UAT is not). */
    public java.time.LocalDate businessDate(String hotelId) {
        var context = ohip.get(hotelId, "/ent/config/v1/hotels/{h}/operaContext", hotelId).body();
        var date = context == null ? "" : context.path("hotelContext").path("businessDate").asText("");
        if (date.isBlank()) {
            throw new PmsTransientException("Opera gave no business date for " + hotelId);
        }
        return java.time.LocalDate.parse(date.substring(0, 10));
    }

    /** The reservation's departure, as Opera has it. */
    public static java.util.Optional<java.time.LocalDate> departure(JsonNode reservation) {
        var date = reservation.path("roomStay").path("departureDate").asText("");
        return date.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(java.time.LocalDate.parse(date.substring(0, 10)));
    }

    /**
     * Before the reservation's departure — the business date is earlier —, Opera checks out nothing
     * (FOF00107 «The guest's departure is not scheduled for today», whatever the event type): the
     * reservation is first made an early departure, which moves its departure to the business date
     * and leaves it due out ({@code PUT /csh/v1/hotels/{h}/reservations/{id}/earlyDeparture}; {@code GET
     * …/earlyDeparture/verify?id=&type=Reservation} says, read-only, whether it may).
     */
    public void earlyDeparture(String hotelId, String reservationId) {
        var body = objectMapper.createObjectNode();
        var criteria = body.putObject("criteria");
        criteria.put("hotelId", hotelId);
        criteria.putObject("reservationIdList").put("id", reservationId).put("type", "Reservation");
        criteria.put("postEarlyDeparturePenalty", false);
        cashier(criteria);
        ohip.put(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/earlyDeparture", body, hotelId, reservationId);
    }

    /** A folio window: its number, what it owes, and whether anything was ever posted to it. */
    public record WindowBalance(int window, BigDecimal balance, String currency, boolean posted) {

        public WindowBalance(int window, BigDecimal balance, String currency) {
            this(window, balance, currency, true);
        }

        public boolean owes() {
            return balance != null && balance.signum() != 0;
        }
    }

    /** The reservation's folio windows with postings — the ones with a folio to settle and generate. Read-only. */
    public List<WindowBalance> openBalances(String hotelId, String reservationId) {
        var answer = ohip.find(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/folios?fetchInstructions=Windowbalances",
                hotelId, reservationId).orElse(null);
        var open = new ArrayList<WindowBalance>();
        for (var w : answer == null ? List.<JsonNode>of() : answer.path("reservationFolioInformation").path("folioWindows")) {
            var balance = w.path("balance").path("amount");
            var amount = balance.isNumber() ? balance.decimalValue() : BigDecimal.ZERO;
            var posted = !w.path("emptyWindow").asBoolean(amount.signum() == 0);
            if (posted || amount.signum() != 0) {
                open.add(new WindowBalance(w.path("folioWindowNo").asInt(1), amount,
                        w.path("balance").path("currencyCode").asText(null), true));
            }
        }
        return open;
    }

    /**
     * Settles a folio window with what the desk collected at the check-out — Opera refuses to check out
     * a folio with a balance (FOF00108 «… has an open folio balance of 306. Settle the balance, then
     * retry»): a payment of the window's balance, with the integration's cashier.
     */
    public void settle(String hotelId, String reservationId, WindowBalance window, String method, String reference) {
        var body = objectMapper.createObjectNode();
        var criteria = body.putObject("criteria");
        criteria.put("hotelId", hotelId);
        criteria.putObject("reservationId").put("id", reservationId).put("type", "Reservation");
        criteria.putObject("paymentMethod").put("paymentMethod", method);
        var amount = criteria.putObject("postingAmount").put("amount", window.balance());
        if (window.currency() != null) {
            amount.put("currencyCode", window.currency());
        }
        criteria.put("folioWindowNo", window.window());
        criteria.put("postingReference", reference);
        criteria.put("action", "Settlefolio");
        cashier(criteria);
        ohip.post(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/payments", body, hotelId, reservationId);
    }

    /** The check-out of a reservation due out today (after {@link #earlyDeparture}, if it was not). */
    public void checkOut(String hotelId, String reservationId) {
        var body = objectMapper.createObjectNode();
        var reservation = body.putObject("reservation");
        reservation.put("hotelId", hotelId);
        reservation.putObject("reservationIdList").put("id", reservationId).put("type", "Reservation");
        reservation.put("eventType", "CheckOut");
        cashier(reservation);
        body.put("verificationOnly", false);
        ohip.post(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/checkOuts", body, hotelId, reservationId);
    }

    /** A folio of the check-out, as Opera's folio history lists it. */
    public record Folio(String number, long folioNo, int window, LocalDate date, BigDecimal amount, String currency,
                        String status) {
    }

    /** The folios the check-out closed — the invoice — newest first. Read-only. */
    public List<Folio> checkOutFolios(String hotelId, String reservationId) {
        var answer = ohip.get(hotelId, "/csh/v1/hotels/{h}/folioHistory?reservationIdId={id}&reservationIdType=Reservation"
                + "&checkOut=true&limit=20", hotelId, reservationId).body();
        var folios = new ArrayList<Folio>();
        for (var f : answer == null ? List.<JsonNode>of() : answer.path("folioHistory")) {
            if (!reservationId.equals(f.path("reservationInfo").path("reservationId").asText(reservationId))) {
                continue;
            }
            var status = f.path("folioStatus").asText("");
            if ("Deposit".equalsIgnoreCase(status) || "Void".equalsIgnoreCase(status)) {
                continue;
            }
            var no = f.path("folioNo").asLong(0);
            folios.add(new Folio(f.path("folioNoWithPrefix").asText(no == 0 ? null : String.valueOf(no)), no,
                    f.path("folioWindowNo").asInt(1),
                    f.hasNonNull("start") ? LocalDate.parse(f.path("start").asText().substring(0, 10)) : null,
                    f.path("folioAmount").path("amount").isNumber() ? f.path("folioAmount").path("amount").decimalValue() : null,
                    f.path("folioAmount").path("currencyCode").asText(null), status));
        }
        folios.sort(Comparator.comparingLong(Folio::folioNo).reversed());
        return folios;
    }

    /**
     * The printable document of the reservation's folio window: Opera generates it (with the cashier)
     * and keeps it as a stored folio, whose report is the PDF. Empty when Opera gives no document —
     * a property that does not store folios, an empty window.
     */
    public Optional<byte[]> folioDocument(String hotelId, String reservationId, int window) {
        var stored = generateFolio(hotelId, reservationId, window);
        if (stored.isEmpty()) {
            log.info("{}/{}: Opera generated the folio of window {} but stored no document", hotelId, reservationId, window);
            return Optional.empty();
        }
        return storedDocument(hotelId, reservationId, stored.get());
    }

    /**
     * Generates the folio of a window — the invoice — with the cashier: Opera refuses to check out a
     * reservation whose folio was not generated (FOF00125 «Generate the folio before checkout»). The
     * stored folio it kept, if the property stores them: its document is the invoice's PDF.
     */
    public Optional<String> generateFolio(String hotelId, String reservationId, int window) {
        var body = objectMapper.createObjectNode();
        var criteria = body.putObject("criteria");
        criteria.put("hotelId", hotelId);
        criteria.putObject("reservationId").put("id", reservationId).put("type", "Reservation");
        criteria.put("folioWindowNo", window);
        criteria.put("eventType", "CheckOut");
        cashier(criteria);
        var generated = ohip.post(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/folios", body, hotelId, reservationId).body();
        for (var w : generated == null ? List.<JsonNode>of() : generated.path("folioWindows")) {
            var id = w.path("storedFolioId").path("id").asText("");
            if (!id.isBlank()) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** The document of a stored folio: its report, the PDF. */
    public Optional<byte[]> storedDocument(String hotelId, String reservationId, String stored) {
        var details = ohip.get(hotelId, "/csh/v1/hotels/{h}/storedFolios/{id}", hotelId, stored).body();
        var url = details == null ? "" : details.path("storedFolioDetails").path("folioReportURL").asText("");
        if (url.isBlank()) {
            log.info("{}/{}: stored folio {} has no report", hotelId, reservationId, stored);
            return Optional.empty();
        }
        var pdf = ohip.document(hotelId, url);
        return pdf == null || pdf.length == 0 ? Optional.empty() : Optional.of(pdf);
    }

    /** Whether the reservation already carries the no-show comment. */
    public static boolean hasNoShowComment(JsonNode reservation) {
        for (var c : reservation.path("comments")) {
            if (c.path("comment").path("text").path("value").asText("").startsWith(NO_SHOW_COMMENT)) {
                return true;
            }
        }
        return false;
    }

    /** A comment on the reservation, alone in the change: nothing else of it is touched. */
    public void comment(String hotelId, String reservationId, String title, String text) {
        var change = objectMapper.createObjectNode();
        ObjectNode reservation = change.putArray("reservations").addObject();
        reservation.putArray("reservationIdList").addObject().put("id", reservationId).put("type", "Reservation");
        reservation.put("hotelId", hotelId);
        reservation.putArray("comments").addObject().putObject("comment")
                .put("commentTitle", title).put("type", "GEN").put("internal", false)
                .putObject("text").put("value", text);
        ohip.put(hotelId, "/rsv/v1/hotels/{h}/reservations/{id}", change, hotelId, reservationId);
    }

    // ── the company billed (pms-fo, asignar-empresa) ───────────────────────────────────────────────

    /** The Opera profile of the company on the reservation, if it has one. */
    public static Optional<String> companyProfileId(JsonNode reservation) {
        for (var p : reservation.path("reservationProfiles").path("reservationProfile")) {
            if ("Company".equals(p.path("reservationProfileType").asText(""))) {
                for (var ref : p.path("profileIdList")) {
                    if ("Profile".equals(ref.path("type").asText("")) && !ref.path("id").asText("").isBlank()) {
                        return Optional.of(ref.path("id").asText());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The company's profile on the reservation, alone in the change — it takes the place of the one
     * there was, if any —, and what the stay costs routed to it: folio window 2 with the company as
     * payee for the accommodation and board's transaction codes ({@code routingCodes}), from arrival to
     * departure. The routing is the cashiering API's (rsv's PUT takes routingInstructions and drops them).
     */
    public void attachCompany(String hotelId, String reservationId, String profileId, java.time.LocalDate arrival,
                              java.time.LocalDate departure, List<String> routingCodes) {
        var change = objectMapper.createObjectNode();
        ObjectNode reservation = change.putArray("reservations").addObject();
        reservation.putArray("reservationIdList").addObject().put("id", reservationId).put("type", "Reservation");
        reservation.put("hotelId", hotelId);
        reservation.putObject("reservationProfiles").putArray("reservationProfile").addObject()
                .put("reservationProfileType", "Company")
                .putArray("profileIdList").addObject().put("id", profileId).put("type", "Profile");
        ohip.put(hotelId, "/rsv/v1/hotels/{h}/reservations/{id}", change, hotelId, reservationId);
        if (routingCodes == null || routingCodes.isEmpty() || arrival == null || departure == null) {
            return;
        }
        var routing = objectMapper.createObjectNode();
        var criteria = routing.putObject("criteria");
        criteria.put("hotelId", hotelId);
        criteria.putObject("reservationId").put("id", reservationId).put("type", "Reservation");
        var folio = criteria.putObject("folio");
        folio.put("folioWindowNo", 2);
        folio.putObject("payeeInfo").putObject("payeeId").put("id", profileId).put("type", "Profile");
        var instruction = folio.putArray("instructions").addObject();
        instruction.putObject("duration").put("daily", true).putObject("timeSpan")
                .put("startDate", arrival.toString()).put("endDate", departure.toString());
        var codes = instruction.putArray("transactionCodes");
        routingCodes.forEach(code -> codes.addObject().put("transactionCode", code));
        ohip.put(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/routingInstructions/create", routing, hotelId, reservationId);
    }

    /**
     * No company on the reservation: its Company profile detached, and the routing to it taken away —
     * only the company's: a paying agency routes to window 2 too.
     */
    public void detachCompany(String hotelId, String reservationId, String companyProfileId) {
        if (companyProfileId != null) {
            ohip.delete(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/routingInstructions/folio?folioWindowNo=2&payeeId={p}",
                    hotelId, reservationId, companyProfileId);
        }
        var change = objectMapper.createObjectNode();
        ObjectNode reservation = change.putArray("reservations").addObject();
        reservation.putArray("reservationIdList").addObject().put("id", reservationId).put("type", "Reservation");
        reservation.put("hotelId", hotelId);
        // a Company entry without a profile: OHIP takes it as «no company» (validated on XMAR, 2026-10-13)
        reservation.putObject("reservationProfiles").putArray("reservationProfile").addObject()
                .put("reservationProfileType", "Company");
        ohip.put(hotelId, "/rsv/v1/hotels/{h}/reservations/{id}", change, hotelId, reservationId);
    }

    // ── the folio: the desk's charges (pms-fo, registrar-cargo / anular-cargo) ─────────────────────

    /** A posting on the reservation's folio, as Opera lists it. */
    public record Posting(String transactionNo, String transactionCode, BigDecimal amount, String reference, String remark,
                          int window) {
    }

    /**
     * The postings on the reservation's folio windows now — read-only ({@code GET …/folios
     * ?fetchInstructions=Postings}). Opera nests them in each window's folios; read wherever they are.
     */
    public List<Posting> postings(String hotelId, String reservationId) {
        // Without summaryOnly=false XMAR answers the windows' totals and no postings at all.
        var answer = ohip.find(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/folios?fetchInstructions=Postings"
                + "&summaryOnly=false&limit=200", hotelId, reservationId).orElse(null);
        var found = new ArrayList<Posting>();
        var seen = new java.util.HashSet<String>();
        for (var w : answer == null ? List.<JsonNode>of() : answer.path("reservationFolioInformation").path("folioWindows")) {
            collect(w, w.path("folioWindowNo").asInt(1), found, seen);
        }
        return found;
    }

    static void collect(JsonNode node, int window, List<Posting> found, java.util.Set<String> seen) {
        if (node.isArray()) {
            node.forEach(n -> collect(n, window, found, seen));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        var no = node.path("transactionNo").asText("");
        if (!no.isBlank() && node.has("transactionCode")) {
            if (seen.add(no)) {
                var amount = node.path("postedAmount").path("amount");
                if (!amount.isNumber()) {
                    amount = node.path("transactionAmount");
                }
                // Opera pads the reference it keeps with a blank ("FO:L-F0909D43 "): trimmed, it is ours.
                var reference = node.path("reference").asText(null);
                found.add(new Posting(no, node.path("transactionCode").asText(null),
                        amount.isNumber() ? amount.decimalValue() : null, reference == null ? null : reference.trim(),
                        node.path("remark").asText(null), node.path("folioWindowNo").asInt(window)));
            }
            return;
        }
        node.forEach(n -> collect(n, window, found, seen));
    }

    /** The posting of the folio carrying this reference, if Opera has one. */
    public Optional<Posting> posting(String hotelId, String reservationId, String reference) {
        return postings(hotelId, reservationId).stream().filter(p -> reference.equals(p.reference())).findFirst();
    }

    /**
     * Posts a charge to the reservation's folio (window 1) with the integration's cashier ({@code POST
     * /csh/v1/hotels/{h}/reservations/{id}/charges}): the transaction code, the amount (negative: a
     * reversal), and the reference the posting is found by again — what makes posting it idempotent.
     * Opera's answer carries no transaction number: it is read back by the reference.
     */
    public void postCharge(String hotelId, String reservationId, String transactionCode, BigDecimal amount, String currency,
                           String reference, String remark) {
        // As Oracle's own examples of postBillingCharges: the reservation in the path only, and the
        // cashier on the criteria — a body with the reservation in the criteria, the cashier on the
        // charge and a window got OHIP 500s from XMAR (2026-09-30).
        var body = objectMapper.createObjectNode();
        var criteria = body.putObject("criteria");
        criteria.put("hotelId", hotelId);
        var charge = criteria.putArray("charges").addObject();
        charge.put("transactionCode", transactionCode);
        var price = charge.putObject("price").put("amount", amount);
        if (currency != null && !currency.isBlank()) {
            price.put("currencyCode", currency);
        }
        charge.put("postingQuantity", 1);
        charge.put("postingReference", reference);
        if (remark != null) {
            charge.put("postingRemark", remark.length() > 200 ? remark.substring(0, 200) : remark);
        }
        criteria.put("postIt", false);
        cashier(criteria);
        ohip.post(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/charges", body, hotelId, reservationId);
    }

    /**
     * Posts a payment to the reservation's folio (window 1) with the integration's cashier ({@code POST
     * /csh/v1/hotels/{h}/reservations/{id}/payments}, action {@code Billing}): the payment method, the
     * amount (negative: a refund, with the original's transaction number), and the reference the posting
     * is found by again — what makes posting it idempotent. Opera's answer carries no transaction number:
     * it is read back by the reference.
     */
    public void postPayment(String hotelId, String reservationId, String method, BigDecimal amount, String currency,
                            String reference, String remark, String originalTransactionNo) {
        var body = objectMapper.createObjectNode();
        var criteria = body.putObject("criteria");
        criteria.put("hotelId", hotelId);
        criteria.putObject("reservationId").put("id", reservationId).put("type", "Reservation");
        criteria.putObject("paymentMethod").put("paymentMethod", method);
        var posting = criteria.putObject("postingAmount").put("amount", amount);
        if (currency != null && !currency.isBlank()) {
            posting.put("currencyCode", currency);
        }
        criteria.put("postingReference", reference);
        if (remark != null) {
            criteria.put("postingRemark", remark.length() > 200 ? remark.substring(0, 200) : remark);
        }
        criteria.put("folioWindowNo", 1);
        criteria.put("action", "Billing");
        if (originalTransactionNo != null && !originalTransactionNo.isBlank()) {
            try {
                criteria.put("originalTransactionNo", Long.parseLong(originalTransactionNo.trim()));
            } catch (NumberFormatException e) {
                // not a number Opera would know: the refund goes without it
            }
        }
        cashier(criteria);
        ohip.post(hotelId, "/csh/v1/hotels/{h}/reservations/{id}/payments", body, hotelId, reservationId);
    }

    /** A room of the property, as Opera's housekeeping has it now. */
    public record RoomState(String roomId, String roomType, String housekeeping, String frontOffice) {
    }

    /** The property's rooms of a type, with their housekeeping and front office status. Read-only. */
    public List<RoomState> rooms(String hotelId, String roomType) {
        return rooms(hotelId, roomType, null);
    }

    /**
     * The property's rooms of a type — or the one room given, which is cheaper — with their
     * housekeeping and front office status. Read-only.
     */
    public List<RoomState> rooms(String hotelId, String roomType, String roomId) {
        var answer = roomId != null && !roomId.isBlank()
                ? ohip.get(hotelId, "/fof/v1/hotels/{h}/rooms?fromRoomNumber={r}&toRoomNumber={r}&limit=5", hotelId, roomId,
                        roomId).body()
                : ohip.get(hotelId, "/fof/v1/hotels/{h}/rooms?roomType={t}&limit=100", hotelId, roomType).body();
        var rooms = new ArrayList<RoomState>();
        for (var r : answer == null ? List.<JsonNode>of() : answer.path("hotelRoomsDetails").path("room")) {
            var status = r.path("housekeeping").path("roomStatus");
            if (roomId != null && !roomId.isBlank() && !roomId.equals(r.path("roomId").asText())) {
                continue;
            }
            rooms.add(new RoomState(r.path("roomId").asText(), r.path("roomType").path("roomType").asText(roomType),
                    status.path("roomStatus").asText(null), status.path("frontOfficeStatus").asText(null)));
        }
        return rooms;
    }

    void cashier(ObjectNode node) {
        if (properties.cashierId() != null) {
            try {
                node.put("cashierId", Long.parseLong(properties.cashierId().trim()));
            } catch (NumberFormatException e) {
                node.put("cashierId", properties.cashierId().trim());
            }
        }
    }
}
