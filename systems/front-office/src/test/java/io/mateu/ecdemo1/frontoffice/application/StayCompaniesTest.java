package io.mateu.ecdemo1.frontoffice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.mateu.ecdemo1.frontoffice.domain.company.Companies;
import io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.WriteStay;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.PmsStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The company a stay is billed to: Opera's reservation brings it; the desk sets it, changes it or takes
 * it away — the PMS told in the same transaction —, and a projection of the reservation as it was before
 * does not undo the desk's choice until Opera shows it; the company's data corrected go to the ERP.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:stay-companies;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    "frontoffice.pms-hotel=XMAR"})
class StayCompaniesTest {

  @Autowired PmsStays pms;
  @Autowired StayCompanies companies;
  @Autowired PmsLinks links;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean Companies erp;

  static final Companies.Company ACME = new Companies.Company("ACME", "Acme Corporation Ltd", "B12345678",
      "Calle Mayor 1", "Madrid", "28013", "ES", "billing@acme.example", null);

  static WriteStay stay(String rid, String locator, String version, String companyCode, String companyName) {
    return new WriteStay(UUID.randomUUID().toString(), "XMAR", rid, "C" + rid, locator, List.of(), version,
        PmsStatus.RESERVED, new FrontOfficeCommand.Person("C-" + rid, "P" + rid, "Nora Moreau", null, null, null),
        List.of(), "STDK", "406484DIRXM", null, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 13), 2,
        "Central Reservation", new BigDecimal("372.00"), "EUR", null, companyCode, companyName);
  }

  List<String> events() {
    return jdbc.queryForList("select payload from outbox_message where payload like '%company-changed%' order by seq",
        String.class);
  }

  @Test
  void theCompanyOnOperasReservationIsTheStays() {
    pms.take(stay("R-CO1", "LOC-CO1", "2026-10-13T10:00:00", "09900171", "BOOKING REST OF EUROPE (CRE)"));

    assertThat(companies.of("LOC-CO1")).hasValue(new PmsLinks.Company("09900171", "BOOKING REST OF EUROPE (CRE)"));
  }

  @Test
  void theDesksCompanyGoesToThePmsAndOperaAsItWasDoesNotUndoIt() {
    when(erp.byCode("ACME")).thenReturn(Optional.of(ACME));
    pms.take(stay("R-CO2", "LOC-CO2", "2026-10-13T10:00:00", null, null));
    var before = events().size();

    companies.set("LOC-CO2", "ACME", "ana");

    assertThat(companies.of("LOC-CO2")).hasValue(new PmsLinks.Company("ACME", "Acme Corporation Ltd"));
    assertThat(events()).hasSize(before + 1);
    assertThat(events().getLast()).contains("\"companyCode\":\"ACME\"").contains("\"pmsReservationId\":\"R-CO2\"");

    // Opera, before the company reached it: the desk's stays
    pms.take(stay("R-CO2", "LOC-CO2", "2026-10-13T10:05:00", null, null));
    assertThat(companies.of("LOC-CO2")).hasValue(new PmsLinks.Company("ACME", "Acme Corporation Ltd"));
    // Opera with it: Opera's again — and a later change in Opera wins
    pms.take(stay("R-CO2", "LOC-CO2", "2026-10-13T10:10:00", "ACME", "ACME CORPORATION LTD"));
    assertThat(companies.of("LOC-CO2")).hasValue(new PmsLinks.Company("ACME", "ACME CORPORATION LTD"));
    pms.take(stay("R-CO2", "LOC-CO2", "2026-10-13T10:15:00", null, null));
    assertThat(companies.of("LOC-CO2")).isEmpty();
  }

  @Test
  void theDeskTakesTheCompanyAway() {
    pms.take(stay("R-CO3", "LOC-CO3", "2026-10-13T10:00:00", "ACME", "Acme Corporation Ltd"));

    companies.set("LOC-CO3", null, "ana");

    assertThat(companies.of("LOC-CO3")).isEmpty();
    assertThat(events().getLast()).contains("\"companyCode\":null").contains("\"stayId\":\"LOC-CO3\"");
  }

  @Test
  void theCompanysDataCorrectedGoToTheErpAndTheStayShowsTheNewName() {
    when(erp.byCode("ACME")).thenReturn(Optional.of(ACME));
    pms.take(stay("R-CO4", "LOC-CO4", "2026-10-13T10:00:00", "ACME", "Acme Corporation Ltd"));
    var corrected = new Companies.Company("ACME", "Acme Corp SL", "B87654321", "Calle Nueva 2", "Palma", "07001", "ES",
        "facturas@acme.example", "+34 971 000 000");

    companies.correct("LOC-CO4", corrected, "ana");

    verify(erp).update(corrected);
    assertThat(companies.of("LOC-CO4")).hasValue(new PmsLinks.Company("ACME", "Acme Corp SL"));
  }

  @Test
  void anInvoiceIsBilledToTheCompanyWithItsData() {
    assertThat(ACME.billTo()).isEqualTo("Acme Corporation Ltd · B12345678 · Calle Mayor 1, 28013 Madrid (ES)");
  }
}
