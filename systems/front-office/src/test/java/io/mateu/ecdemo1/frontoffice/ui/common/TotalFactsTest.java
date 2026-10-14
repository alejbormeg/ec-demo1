package io.mateu.ecdemo1.frontoffice.ui.common;

import io.mateu.uidl.data.Fact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The stay's banner says what was agreed in the CRS and, when Opera bills more, by how much. */
class TotalFactsTest {

  @Test
  void whenOperaAddsPackagesBothPricesShowTheCrsFirst() {
    var facts = GuestHeaders.totalFacts(new BigDecimal("346.00"), new BigDecimal("306.00"), "Directo · Walk In");

    assertThat(facts).extracting(Fact::label).containsExactly("PRECIO CRS / OPERA", "AGENCIA", "EMPRESA");
    assertThat(facts.get(0).value()).startsWith(GuestHeaders.euros(new BigDecimal("306.00")) + " / "
            + GuestHeaders.euros(new BigDecimal("346.00")))
        .contains("+" + GuestHeaders.euros(new BigDecimal("40.00")) + " paquetes de Opera");
  }

  @Test
  void equalOrUnknownIsOneTotal() {
    assertThat(GuestHeaders.totalFacts(new BigDecimal("306"), new BigDecimal("306.00"), "x"))
        .extracting(Fact::label).containsExactly("TOTAL RESERVA", "AGENCIA", "EMPRESA");
    assertThat(GuestHeaders.totalFacts(new BigDecimal("306"), null, "x"))
        .extracting(Fact::label).containsExactly("TOTAL RESERVA", "AGENCIA", "EMPRESA");
  }

  @Test
  void onTheReservationsPageTheTillTheCompanyAndTheTabletAreTheFactsButtons() {
    var facts = GuestHeaders.totalFacts(new BigDecimal("346.00"), new BigDecimal("306.00"), "x", "Acme", true, "Sin firmar");

    // four at most: Redwood's page header shows no more
    assertThat(facts).extracting(Fact::label).containsExactly("PRECIO CRS / OPERA", "AGENCIA", "EMPRESA", "CHECK-IN");
    assertThat(facts).extracting(Fact::actionId).containsExactly("abrirCaja", null, "opEmpresa", "enviarTableta");
  }

  @Test
  void theCompanyBilledIsOnTheSameLineOrADash() {
    assertThat(GuestHeaders.totalFacts(new BigDecimal("306"), null, "x", "Acme Corporation Ltd").getLast().value())
        .isEqualTo("Acme Corporation Ltd");
    assertThat(GuestHeaders.totalFacts(new BigDecimal("306"), null, "x").getLast().value()).isEqualTo("—");
  }
}
