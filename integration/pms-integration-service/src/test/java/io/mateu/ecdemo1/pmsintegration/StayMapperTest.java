package io.mateu.ecdemo1.pmsintegration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.Person;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.PmsStatus;
import io.mateu.ecdemo1.pmsintegration.frontoffice.StayMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Opera reservation into the front office's stay — in the shapes OHIP UAT answers for XMAR: one
 * the CRS wrote (its locator under the run's context), one born in Opera, a cancellation and a
 * no-show.
 */
class StayMapperTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final StayMapper.Context NO_CUSTOMER = new StayMapper.Context("ECDEMO1-09271718", Set.of("NOSHOW"), null, null);

    /** As OHIP answers GET …/reservations/{id}?fetchInstructions=Reservation&fetchInstructions=Packages. */
    static JsonNode reservation(String status, String cancellation, String references) throws Exception {
        return JSON.readTree("""
                {
                  "reservationIdList": [{"id": "39484601", "type": "Reservation"},
                                        {"id": "268334379", "type": "Confirmation"}],
                  "externalReferences": [%s],
                  "roomStay": {
                    "roomRates": [{
                      "total": {"amountBeforeTax": 186},
                      "rates": {"rate": [{"base": {"amountBeforeTax": 186, "currencyCode": "MUR"}}]},
                      "guestCounts": {"adults": 2, "children": 0},
                      "roomType": "STDK", "ratePlanCode": "406484DIRXM",
                      "sourceCode": "CRSN", "sourceCodeDescription": "Central Reservation"
                    }],
                    "guestCounts": {"adults": 2, "children": 1},
                    "arrivalDate": "2026-11-10", "departureDate": "2026-11-12",
                    "total": {"amountBeforeTax": 372}
                  },
                  "reservationGuests": [{
                    "profileInfo": {
                      "profileIdList": [{"id": "20546095", "type": "Profile"}],
                      "profile": {
                        "customer": {"personName": [{"givenName": "Nora", "surname": "Moreau", "nameType": "Primary"},
                                                    {"nameType": "External"}]},
                        "telephones": {"telephoneInfo": [
                          {"telephone": {"phoneTechType": "WEBPAGE", "phoneUseType": "WEB", "phoneNumber": "WRC3C25G-1"}},
                          {"telephone": {"phoneTechType": "PHONE", "phoneUseType": "MOBILE", "phoneNumber": "+32 450323958", "primaryInd": true}}]},
                        "emails": {"emailInfo": [{"email": {"emailAddress": "nora.moreau@example.com", "primaryInd": true}}]}
                      }
                    },
                    "primary": true
                  }],
                  "reservationPackages": [{"packageCode": "BRKFST", "startDate": "2026-11-10", "endDate": "2026-11-12"}],
                  "reservationStatus": "%s",
                  %s
                  "createDateTime": "2026-09-27 21:50:02.0",
                  "lastModifyDateTime": "2026-09-27 21:57:59.0"
                }
                """.formatted(references, status, cancellation));
    }

    static final String CRS_REFS = """
            {"id": "268334379", "idExtension": 1, "idContext": "OPERA"}, {"id": "3PJ492", "idContext": "ECDEMO1-09271718"}""";

    @Test
    void aReservationTheCrsWroteIsTheStayOfItsLocatorWithOperasCodes() throws Exception {
        var stay = StayMapper.toWriteStay("XMAR", reservation("Reserved", "", CRS_REFS), NO_CUSTOMER);
        assertThat(stay.pmsReservationId()).isEqualTo("39484601");
        assertThat(stay.confirmationNumber()).isEqualTo("268334379");
        assertThat(stay.crsLocator()).isEqualTo("3PJ492");
        assertThat(stay.externalReferences()).isEmpty();
        assertThat(stay.pmsVersion()).isEqualTo("2026-09-27T21:57:59");
        assertThat(stay.status()).isEqualTo(PmsStatus.RESERVED);
        assertThat(stay.roomTypeCode()).isEqualTo("STDK");
        assertThat(stay.ratePlanCode()).isEqualTo("406484DIRXM");
        assertThat(stay.boardCode()).isEqualTo("BRKFST");
        assertThat(stay.checkIn()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(stay.checkOut()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(stay.pax()).isEqualTo(3);
        assertThat(stay.total()).isEqualByComparingTo(new BigDecimal("372"));
        // the CRS's price, as Opera keeps it fixed: the rate alone
        assertThat(stay.agreedTotal()).isEqualByComparingTo(new BigDecimal("372"));
        assertThat(stay.currency()).isEqualTo("MUR");
        assertThat(stay.agency()).isEqualTo("Directo · Central Reservation");
        assertThat(stay.key()).isEqualTo("XMAR/39484601");
    }

    @Test
    void theHolderIsOperasPrimaryGuestWithoutTheWebPageOperaKeepsAsAPhone() throws Exception {
        var holder = StayMapper.toWriteStay("XMAR", reservation("Reserved", "", CRS_REFS), NO_CUSTOMER).holder();
        assertThat(holder.name()).isEqualTo("Nora Moreau");
        assertThat(holder.pmsProfileId()).isEqualTo("20546095");
        assertThat(holder.email()).isEqualTo("nora.moreau@example.com");
        assertThat(holder.phone()).isEqualTo("+32 450323958");
        assertThat(holder.customerId()).isNull();
    }

    @Test
    void whenTheMdmKnowsTheProfileItsCustomerAndGoldenRecordGoOverOperas() throws Exception {
        var context = new StayMapper.Context("ECDEMO1-09271718", Set.of("NOSHOW"), "C-7",
                new Person("C-7", "20546095", "Nora Moreau Dupont", "X1234567", null, "+33 600000000"));
        var holder = StayMapper.toWriteStay("XMAR", reservation("Reserved", "", CRS_REFS), context).holder();
        assertThat(holder.customerId()).isEqualTo("C-7");
        assertThat(holder.name()).isEqualTo("Nora Moreau Dupont");
        assertThat(holder.document()).isEqualTo("X1234567");
        assertThat(holder.email()).isEqualTo("nora.moreau@example.com");
        assertThat(holder.phone()).isEqualTo("+33 600000000");
    }

    @Test
    void aReservationBornInOperaHasNoCrsLocatorAndKeepsItsReferences() throws Exception {
        var refs = """
                {"id": "263428862", "idContext": "OPERA"}, {"id": "WRC3C25G-1", "idContext": "CRS"}""";
        var stay = StayMapper.toWriteStay("XMAR", reservation("Reserved", "", refs), NO_CUSTOMER);
        assertThat(stay.crsLocator()).isNull();
        assertThat(stay.externalReferences()).containsExactly("WRC3C25G-1");
    }

    @Test
    void anOldRunsContextIsNotThisCrsLocator() throws Exception {
        var refs = """
                {"id": "3PJ492", "idContext": "ECDEMO1"}""";
        var stay = StayMapper.toWriteStay("XMAR", reservation("Reserved", "", refs), NO_CUSTOMER);
        assertThat(stay.crsLocator()).isNull();
        assertThat(stay.externalReferences()).containsExactly("3PJ492");
    }

    @Test
    void roomOnlyHasNoBoard() throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) reservation("Reserved", "", CRS_REFS);
        node.remove("reservationPackages");
        assertThat(StayMapper.toWriteStay("XMAR", node, NO_CUSTOMER).boardCode()).isNull();
    }

    @Test
    void aCancellationIsCancelled() throws Exception {
        var stay = StayMapper.toWriteStay("XMAR", reservation("Cancelled", """
                "cancellation": {"description": "Cancelled in the CRS (reason OTR)", "code": "OTROS"},""", CRS_REFS), NO_CUSTOMER);
        assertThat(stay.status()).isEqualTo(PmsStatus.CANCELLED);
    }

    @Test
    void aCancellationWithANoShowCodeIsANoShowCostingWhatOperaStillCharges() throws Exception {
        var stay = StayMapper.toWriteStay("XMAR", reservation("Cancelled", """
                "cancellation": {"description": "No show: cancelled in the CRS with a fee of 139.50 EUR (of 558.00)", "code": "NOSHOW"},""",
                CRS_REFS), NO_CUSTOMER);
        assertThat(stay.status()).isEqualTo(PmsStatus.NO_SHOW);
        assertThat(stay.total()).isEqualByComparingTo(new BigDecimal("372"));
    }

    @Test
    void operasOwnNoShowIsANoShow() throws Exception {
        assertThat(StayMapper.toWriteStay("XMAR", reservation("NoShow", "", CRS_REFS), NO_CUSTOMER).status())
                .isEqualTo(PmsStatus.NO_SHOW);
    }

    @Test
    void guestsOperaHasInTheHouseOrCheckedOutAreSoForTheFrontOffice() throws Exception {
        assertThat(StayMapper.toWriteStay("XMAR", reservation("InHouse", "", CRS_REFS), NO_CUSTOMER).status())
                .isEqualTo(PmsStatus.IN_HOUSE);
        assertThat(StayMapper.toWriteStay("XMAR", reservation("CheckedOut", "", CRS_REFS), NO_CUSTOMER).status())
                .isEqualTo(PmsStatus.CHECKED_OUT);
    }

    @Test
    void aTravelAgentOnTheReservationIsWhoSoldIt() throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) reservation("Reserved", "", CRS_REFS);
        node.set("reservationProfiles", JSON.readTree("""
                {"reservationProfile": [{"profile": {"company": {"companyName": "P.DIFERIDO RIU CLASS CA"}},
                                         "reservationProfileType": "TravelAgent"}]}"""));
        assertThat(StayMapper.toWriteStay("XMAR", node, NO_CUSTOMER).agency()).isEqualTo("P.DIFERIDO RIU CLASS CA");
    }

    @Test
    void aCompanyOnTheReservationIsWhoIsBilledNotWhoSoldIt() throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) reservation("Reserved", "", CRS_REFS);
        // as XMAR has it (reservation 28846078): the profile's id, and its CorporateId — the partner's code
        node.set("reservationProfiles", JSON.readTree("""
                {"reservationProfile": [
                   {"profileIdList": [{"id": "16120788", "type": "Profile"}],
                    "profile": {"company": {"companyName": "P.DIFERIDO RIU CLASS CA"}}, "reservationProfileType": "TravelAgent"},
                   {"profileIdList": [{"id": "16120716", "type": "Profile"}, {"id": "09900171", "type": "CorporateId"}],
                    "profile": {"company": {"companyName": "BOOKING REST OF EUROPE (CRE)"}}, "reservationProfileType": "Company"}]}"""));
        var w = StayMapper.toWriteStay("XMAR", node, NO_CUSTOMER);
        assertThat(w.agency()).isEqualTo("P.DIFERIDO RIU CLASS CA");
        assertThat(w.companyName()).isEqualTo("BOOKING REST OF EUROPE (CRE)");
        assertThat(StayMapper.company(node)).hasValue(new StayMapper.Company("16120716", "BOOKING REST OF EUROPE (CRE)", "09900171"));
        assertThat(StayMapper.withCompanyCode(w, "09900171").companyCode()).isEqualTo("09900171");
    }

    @Test
    void aCompanyAloneIsNoAgency() throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) reservation("Reserved", "", CRS_REFS);
        node.set("reservationProfiles", JSON.readTree("""
                {"reservationProfile": [{"profileIdList": [{"id": "16120716", "type": "Profile"}],
                    "profile": {"company": {"companyName": "ACME"}}, "reservationProfileType": "Company"}]}"""));
        var w = StayMapper.toWriteStay("XMAR", node, NO_CUSTOMER);
        assertThat(w.agency()).startsWith("Directo");
        assertThat(w.companyName()).isEqualTo("ACME");
        assertThat(w.companyCode()).isNull();
    }

    @Test
    void operasTimesBecomeIsoLocalDateTimes() {
        assertThat(StayMapper.isoLocal("2026-03-13 22:01:45.0")).isEqualTo("2026-03-13T22:01:45");
        assertThat(StayMapper.isoLocal("2026-03-13")).isEqualTo("2026-03-13T00:00:00");
        assertThat(StayMapper.isoLocal(null)).isEmpty();
    }

    @Test
    void thePackagesOperaPostsApartAreInTheStaysTotal() throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"reservationIdList": [{"id": "39485830", "type": "Reservation"}],
                 "reservationStatus": "Reserved",
                 "roomStay": {"arrivalDate": "2026-05-13", "departureDate": "2026-05-14", "total": {"amountBeforeTax": 306},
                              "roomRates": [{"roomType": "SJMB", "ratePlanCode": "406484DIRXM",
                                             "rates": {"rate": [{"base": {"amountBeforeTax": 306, "currencyCode": "MUR"}}]}}]},
                 "reservationPackages": [
                   {"packageCode": "BRKFST", "packageHeaderType": {"postingAttributes": {"addToRate": false}},
                    "scheduleList": [{"consumptionDate": "2026-05-13", "unitPrice": 40, "totalQuantity": 1, "computedResvPrice": 40}]},
                   {"packageCode": "INCL", "packageHeaderType": {"postingAttributes": {"addToRate": true}},
                    "scheduleList": [{"consumptionDate": "2026-05-13", "unitPrice": 10, "totalQuantity": 1, "computedResvPrice": 10}]}]}
                """);

        var stay = io.mateu.ecdemo1.pmsintegration.frontoffice.StayMapper.toWriteStay("XMAR", json,
                NO_CUSTOMER);

        // The rate (306) and the breakfast Opera posts apart (40): what Opera's folio will carry for the stay.
        assertThat(stay.total()).isEqualByComparingTo(new BigDecimal("346"));
        // born in Opera (no CRS locator): no price the CRS agreed
        assertThat(stay.agreedTotal()).isNull();
    }

    static io.mateu.ecdemo1.integration.model.reservation.Person crsPerson(String first, String last) {
        return new io.mateu.ecdemo1.integration.model.reservation.Person(first, last,
                io.mateu.ecdemo1.integration.model.reservation.GuestType.ADULT, null, null, null, "ES", null, null, null);
    }

    static io.mateu.ecdemo1.integration.model.reservation.Reservation crs(
            List<io.mateu.ecdemo1.integration.model.reservation.Person> guests) {
        return new io.mateu.ecdemo1.integration.model.reservation.Reservation("MRU01", "QS4KX8", 1,
                io.mateu.ecdemo1.integration.model.reservation.ReservationStatus.CONFIRMED, "WEB", null, null,
                java.time.LocalDate.of(2026, 11, 2), java.time.LocalDate.of(2026, 11, 5), "EUR", crsPerson("Ebba", "Johansson"),
                List.of(new io.mateu.ecdemo1.integration.model.reservation.Room(1, "JS-STD", "DIRECTA", "AD", 2, List.of(),
                        guests, List.of())), List.of(), new java.math.BigDecimal("918"), null, null);
    }

    @Test
    void theCompanionsOperaDoesNotNameAreTheCrsGuests() throws Exception {
        var stay = StayMapper.toWriteStay("XMAR", reservation("Reserved", "", CRS_REFS), NO_CUSTOMER);
        var bare = new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.WriteStay(stay.commandId(),
                stay.pmsHotelCode(), stay.pmsReservationId(), stay.confirmationNumber(), stay.crsLocator(),
                stay.externalReferences(), stay.pmsVersion(), stay.status(),
                new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.Person(null, "P1", "Ebba Johansson",
                        null, null, null),
                List.of(), stay.roomTypeCode(), stay.ratePlanCode(), stay.boardCode(), stay.checkIn(), stay.checkOut(), 2,
                stay.agency(), stay.total(), stay.currency());

        var named = StayMapper.withCrsGuests(bare, crs(List.of(crsPerson("Ebba", "Johansson"), crsPerson("Lucía", "Pérez"))));

        assertThat(named.companions()).extracting(c -> c.name()).containsExactly("Lucía Pérez");
        assertThat(named.holder().name()).isEqualTo("Ebba Johansson");
        // no more than the stay's pax, and nothing to do without the CRS's booking
        assertThat(StayMapper.withCrsGuests(bare, crs(List.of(crsPerson("Lucía", "Pérez"), crsPerson("Ivo", "Sanz"))))
                .companions()).hasSize(1);
        assertThat(StayMapper.withCrsGuests(bare, null)).isSameAs(bare);
    }
}
