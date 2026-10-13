package io.mateu.ecdemo1.pmsintegration.frontoffice;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.Person;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.PmsStatus;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.WriteStay;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An Opera reservation, as Opera holds it, into the stay the front office takes — Opera's model to
 * the front office's, nothing from the CRS. Codes stay Opera's: the front office reads them with the
 * catalogue the same integration gave it.
 *
 * <p>What it reads, in the shapes a real tenant answers (OHIP UAT, XMAR): the ids
 * ({@code reservationIdList}: Reservation, Confirmation), the external references — the CRS's
 * locator is the one in the integration's context, the rest go along for the front office to
 * recognise a stay by —, the first room rate's room type, rate plan and source, the stay's dates,
 * guests and total, the packages (the board: the first one; none is room only), the primary guest
 * and any other guest on it, the travel agent or company, whether the guests are in the house or
 * checked out, and how it was cancelled: a cancellation
 * whose code is a no-show code, or whose description says «No show», is a no-show.
 */
public final class StayMapper {

    /** What a reservation is written with besides itself: whose context the CRS locator is in, and who the holder is in the MDM. */
    public record Context(String crsContext, Set<String> noShowCodes, String customerId, Person master) {
    }

    private StayMapper() {
    }

    public static WriteStay toWriteStay(String pmsHotelCode, JsonNode r, Context context) {
        var rate = r.path("roomStay").path("roomRates").path(0);
        var stay = r.path("roomStay");
        String crsLocator = null;
        var references = new ArrayList<String>();
        for (var ref : r.path("externalReferences")) {
            var ctx = ref.path("idContext").asText("");
            var id = ref.path("id").asText(null);
            if (id == null || id.isBlank() || "OPERA".equals(ctx)) {
                continue;
            }
            if (ctx.equals(context.crsContext())) {
                crsLocator = id;
            } else {
                references.add(id);
            }
        }
        var counts = stay.path("guestCounts").isMissingNode() ? rate.path("guestCounts") : stay.path("guestCounts");
        var pax = Math.max(1, counts.path("adults").asInt(0) + counts.path("children").asInt(0));
        var holder = holder(r, context);
        return new WriteStay(UUID.randomUUID().toString(), pmsHotelCode, OperaStays.id(r, "Reservation"),
                OperaStays.id(r, "Confirmation"), crsLocator, references,
                isoLocal(r.path("lastModifyDateTime").asText(r.path("createDateTime").asText(""))), status(r, context),
                holder, companions(r), text(rate.path("roomType")), text(rate.path("ratePlanCode")), board(r),
                OperaStays.date(stay.path("arrivalDate")), OperaStays.date(stay.path("departureDate")), pax,
                agency(r, rate), total(r, stay, rate, context), currency(rate),
                crsLocator == null ? null : total(stay, rate), null, company(r).map(Company::name).orElse(null));
    }

    /**
     * The company profile on a reservation: Opera's id of it, its name, and its CorporateId — the
     * partner's code, for a profile the integration projected from the ERP (or the chain's own).
     */
    public record Company(String pmsProfileId, String name, String corporateId) {
    }

    /**
     * The company on the reservation — who is billed when neither the agency nor the guest is —: its
     * profile of type Company, if it has one.
     */
    public static java.util.Optional<Company> company(JsonNode r) {
        for (var p : r.path("reservationProfiles").path("reservationProfile")) {
            if (!"Company".equals(p.path("reservationProfileType").asText(""))) {
                continue;
            }
            String id = null;
            String corporateId = null;
            for (var ref : p.path("profileIdList")) {
                var value = ref.path("id").asText("");
                if (value.isBlank()) {
                    continue;
                }
                switch (ref.path("type").asText("")) {
                    case "Profile" -> id = value;
                    case "CorporateId" -> corporateId = value;
                    default -> { }
                }
            }
            var name = p.path("profile").path("company").path("companyName").asText(null);
            if (id != null || (name != null && !name.isBlank())) {
                return java.util.Optional.of(new Company(id, name == null || name.isBlank() ? null : name, corporateId));
            }
        }
        return java.util.Optional.empty();
    }

    /** The command with the company's code in the ERP (the partner its profile is), when it is one. */
    public static WriteStay withCompanyCode(WriteStay w, String companyCode) {
        return new WriteStay(w.commandId(), w.pmsHotelCode(), w.pmsReservationId(), w.confirmationNumber(), w.crsLocator(),
                w.externalReferences(), w.pmsVersion(), w.status(), w.holder(), w.companions(), w.roomTypeCode(),
                w.ratePlanCode(), w.boardCode(), w.checkIn(), w.checkOut(), w.pax(), w.agency(), w.total(), w.currency(),
                w.agreedTotal(), companyCode, w.companyName());
    }

    static PmsStatus status(JsonNode r, Context context) {
        var status = r.path("reservationStatus").asText("");
        if ("NoShow".equalsIgnoreCase(status)) {
            return PmsStatus.NO_SHOW;
        }
        // The reception's, recorded in Opera (registrar-checkin, -checkout) — or done in Opera itself.
        if ("InHouse".equalsIgnoreCase(status) || "DueOut".equalsIgnoreCase(status)) {
            return PmsStatus.IN_HOUSE;
        }
        if ("CheckedOut".equalsIgnoreCase(status)) {
            return PmsStatus.CHECKED_OUT;
        }
        if (!"Cancelled".equalsIgnoreCase(status)) {
            return PmsStatus.RESERVED;
        }
        var cancellation = r.path("cancellation");
        var code = cancellation.path("code").asText("");
        var description = cancellation.path("description").asText("");
        return context.noShowCodes().contains(code) || description.toLowerCase().startsWith("no show")
                ? PmsStatus.NO_SHOW : PmsStatus.CANCELLED;
    }

    /** The primary guest as Opera has it; what the MDM's master has, over it, when the MDM knows the customer. */
    static Person holder(JsonNode r, Context context) {
        JsonNode primary = null;
        for (var guest : r.path("reservationGuests")) {
            if (primary == null || guest.path("primary").asBoolean(false)) {
                primary = guest;
                if (guest.path("primary").asBoolean(false)) {
                    break;
                }
            }
        }
        var person = primary == null ? new Person(null, null, "", null, null, null) : person(primary);
        if (context.master() == null) {
            return new Person(context.customerId(), person.pmsProfileId(), person.name(), person.document(), person.email(),
                    person.phone());
        }
        var m = context.master();
        return new Person(context.customerId(), person.pmsProfileId(), or(m.name(), person.name()), or(m.document(), person.document()),
                or(m.email(), person.email()), or(m.phone(), person.phone()));
    }

    /**
     * The stay with the CRS booking's people where Opera has none: Opera's companions first (they carry
     * a profile), then the CRS's guests that are neither the holder nor already among them, by name, up
     * to the stay's pax — so the front office reads «Lucía Pérez», not «Acompañante 2».
     */
    public static WriteStay withCrsGuests(WriteStay w, io.mateu.ecdemo1.integration.model.reservation.Reservation crs) {
        if (crs == null || crs.rooms() == null) {
            return w;
        }
        var companions = new ArrayList<Person>(w.companions() == null ? List.of() : w.companions());
        var named = new java.util.HashSet<String>();
        companions.forEach(c -> named.add(key(c.name())));
        if (w.holder() != null) {
            named.add(key(w.holder().name()));
        }
        if (crs.holder() != null) {
            named.add(key(fullName(crs.holder())));
        }
        for (var room : crs.rooms()) {
            for (var guest : room.guests() == null ? List.<io.mateu.ecdemo1.integration.model.reservation.Person>of() : room.guests()) {
                if (companions.size() >= w.pax() - 1) {
                    break;
                }
                var name = fullName(guest);
                if (name.isBlank() || !named.add(key(name))) {
                    continue;
                }
                companions.add(new Person(null, null, name, guest.documentNumber(), guest.email(), guest.phone()));
            }
        }
        return new WriteStay(w.commandId(), w.pmsHotelCode(), w.pmsReservationId(), w.confirmationNumber(), w.crsLocator(),
                w.externalReferences(), w.pmsVersion(), w.status(), w.holder(), List.copyOf(companions), w.roomTypeCode(),
                w.ratePlanCode(), w.boardCode(), w.checkIn(), w.checkOut(), w.pax(), w.agency(), w.total(), w.currency(),
                w.agreedTotal(), w.companyCode(), w.companyName());
    }

    static String fullName(io.mateu.ecdemo1.integration.model.reservation.Person p) {
        return ((p.firstName() == null ? "" : p.firstName()) + " " + (p.lastName() == null ? "" : p.lastName())).strip();
    }

    static String key(String name) {
        return name == null ? "" : java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    static List<Person> companions(JsonNode r) {
        var list = new ArrayList<Person>();
        var guests = r.path("reservationGuests");
        var hasPrimary = false;
        for (var guest : guests) {
            hasPrimary |= guest.path("primary").asBoolean(false);
        }
        var first = true;
        for (var guest : guests) {
            var isHolder = hasPrimary ? guest.path("primary").asBoolean(false) : first;
            first = false;
            if (!isHolder) {
                list.add(person(guest));
            }
        }
        return list;
    }

    static Person person(JsonNode guest) {
        var profile = guest.path("profileInfo").path("profile");
        String given = null, surname = null;
        for (var name : profile.path("customer").path("personName")) {
            if (name.path("nameType").asText("Primary").equals("Primary") || given == null && surname == null) {
                given = name.path("givenName").asText(null);
                surname = name.path("surname").asText(null);
                if ("Primary".equals(name.path("nameType").asText())) {
                    break;
                }
            }
        }
        String email = null;
        for (var e : profile.path("emails").path("emailInfo")) {
            var address = e.path("email").path("emailAddress").asText(null);
            if (address != null && (email == null || e.path("email").path("primaryInd").asBoolean(false))) {
                email = address;
            }
        }
        String phone = null;
        for (var t : profile.path("telephones").path("telephoneInfo")) {
            var tel = t.path("telephone");
            // A web page kept as a «telephone» (a real tenant has them, WEBPAGE) is no phone.
            if ("WEBPAGE".equals(tel.path("phoneTechType").asText()) || "WEB".equals(tel.path("phoneUseType").asText())) {
                continue;
            }
            var number = tel.path("phoneNumber").asText(null);
            if (number != null && (phone == null || tel.path("primaryInd").asBoolean(false))) {
                phone = number;
            }
        }
        var name = ((given == null ? "" : given) + " " + (surname == null ? "" : surname)).trim();
        return new Person(null, guest.path("profileInfo").path("profileIdList").path(0).path("id").asText(null), name, null,
                email, phone);
    }

    /** The board: the reservation's package — the first, if it has several; none is room only. */
    static String board(JsonNode r) {
        for (var p : r.path("reservationPackages")) {
            var code = p.path("packageCode").asText(null);
            if (code != null && !code.isBlank()) {
                return code;
            }
        }
        return null;
    }

    /**
     * The travel agent that sold it; otherwise the hotel itself, by Opera's source. A company on the
     * reservation is not who sold it but who is billed: {@link #company}.
     */
    static String agency(JsonNode r, JsonNode rate) {
        for (var p : r.path("reservationProfiles").path("reservationProfile")) {
            var type = p.path("reservationProfileType").asText("");
            var name = p.path("profile").path("company").path("companyName").asText(null);
            if (name != null && !name.isBlank() && ("TravelAgent".equals(type) || "Source".equals(type))) {
                return name;
            }
        }
        var source = rate.path("sourceCodeDescription").asText(rate.path("sourceCode").asText(""));
        return source.isBlank() ? "Directo" : "Directo · " + source;
    }

    /**
     * What the stay costs as Opera will charge it: its rate's total and, but for a no-show's fee or a
     * cancellation, the packages Opera posts apart from the rate ({@code addToRate} false) — the board at
     * XMAR (BRKFST, 40 MUR a night): Opera's folio, and so its invoice, carries them, and the front
     * office's accommodation line must too for the totals to match. The CRS's agreed price — the rate
     * alone, which Opera keeps fixed — travels next to it ({@code agreedTotal}), for the desk to see both.
     */
    static BigDecimal total(JsonNode r, JsonNode stay, JsonNode rate, Context context) {
        var total = total(stay, rate);
        var status = status(r, context);
        if (total == null || status == PmsStatus.NO_SHOW || status == PmsStatus.CANCELLED) {
            return total;
        }
        return total.add(packagesApart(r));
    }

    /** The reservation's packages Opera posts apart from the rate, over the stay. */
    static BigDecimal packagesApart(JsonNode r) {
        var sum = BigDecimal.ZERO;
        for (var p : r.path("reservationPackages")) {
            if (p.path("packageHeaderType").path("postingAttributes").path("addToRate").asBoolean(false)) {
                continue;
            }
            for (var day : p.path("scheduleList")) {
                var price = day.path("computedResvPrice");
                if (price.isNumber()) {
                    sum = sum.add(price.decimalValue());
                } else if (day.path("unitPrice").isNumber()) {
                    sum = sum.add(day.path("unitPrice").decimalValue().multiply(BigDecimal.valueOf(day.path("totalQuantity").asInt(1))));
                }
            }
        }
        return sum;
    }

    static BigDecimal total(JsonNode stay, JsonNode rate) {
        var total = stay.path("total").path("amountBeforeTax");
        if (total.isNumber()) {
            return total.decimalValue();
        }
        var sum = BigDecimal.ZERO;
        var any = false;
        for (var r : stay.path("roomRates")) {
            var amount = r.path("total").path("amountBeforeTax");
            if (amount.isNumber()) {
                sum = sum.add(amount.decimalValue());
                any = true;
            }
        }
        return any ? sum : null;
    }

    static String currency(JsonNode rate) {
        return rate.path("rates").path("rate").path(0).path("base").path("currencyCode").asText(null);
    }

    /** Opera's «2026-09-27 21:57:59.0» as an ISO local date-time, «2026-09-27T21:57:59»: what orders the versions. */
    public static String isoLocal(String opera) {
        if (opera == null || opera.isBlank()) {
            return "";
        }
        var s = opera.trim().replace(' ', 'T');
        var dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return s.length() == 10 ? s + "T00:00:00" : s;
    }

    static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    static String or(String value, String otherwise) {
        return value == null || value.isBlank() ? otherwise : value;
    }
}
