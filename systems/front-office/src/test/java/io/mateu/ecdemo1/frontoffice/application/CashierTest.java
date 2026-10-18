package io.mateu.ecdemo1.frontoffice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mateu.ecdemo1.frontoffice.domain.cashier.Payment;
import io.mateu.ecdemo1.frontoffice.domain.folio.Folio;
import io.mateu.ecdemo1.frontoffice.domain.folio.FolioLine;
import io.mateu.ecdemo1.frontoffice.domain.folio.FolioRepository;
import io.mateu.ecdemo1.frontoffice.domain.guest.Guest;
import io.mateu.ecdemo1.frontoffice.domain.guest.GuestRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The desk's cashiering on a stay's account: what is owed, payments and advances by each method, the
 * receipts and the proforma, the credit limit and the cancelled credit.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:cashier;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    "frontoffice.arrivals-briefing.enabled=false"})
class CashierTest {

  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired Cashier cashier;
  @Autowired Receipts receipts;
  @Autowired FolioService folioService;
  @Autowired FolioRepository folios;
  @Autowired GuestRepository guests;
  @Autowired StayRepository stays;
  @Autowired io.mateu.ecdemo1.frontoffice.infra.outbox.CommandOutbox outbox;
  @Autowired PmsStays pms;
  @Autowired io.mateu.ecdemo1.frontoffice.infra.pms.ChargePostings postings;

  /** A stay in the house with 300 of accommodation and 45.50 of minibar on its folio. */
  String inHouse() {
    var n = SEQ.incrementAndGet();
    var stayId = "CJ-" + n;
    guests.save(Guest.fromReservation("C-CJ" + n, "Marta Soler", "X" + n, "marta" + n + "@example.com", null));
    stays.save(Stay.fromReservation(stayId, "C-CJ" + n, "Doble", "Desayuno", LocalDate.now(), LocalDate.now().plusDays(3),
        1, "TUI", new BigDecimal("300.00"), List.of()));
    folios.save(Folio.openFor(Folio.idFor(stayId), stayId, new BigDecimal("300.00"))
        .post(FolioLine.accommodation("Alojamiento x3 noches", new BigDecimal("300.00")))
        .post(FolioLine.charged(io.mateu.ecdemo1.frontoffice.domain.folio.ChargeKind.CONSUMPTION, "MB-01", "Minibar",
            new BigDecimal("45.50"))));
    return stayId;
  }

  @Test
  void whatIsOwedIsTheChargesLessWhatWasCaptured_eachPaymentWithItsReceipt() {
    var stayId = inHouse();
    var a = cashier.account(stayId);
    assertThat(a.charges()).isEqualByComparingTo("345.50");
    assertThat(a.due()).isEqualByComparingTo("345.50");
    assertThat(a.currency()).isEqualTo("EUR");
    assertThat(a.overLimit()).isTrue(); // over the 300 pre-authorized

    var advance = cashier.take(stayId, Payment.Kind.DEPOSIT, Payment.Method.CASH, new BigDecimal("100"), null, null, "ana");
    var card = cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.CARD_PINPAD, new BigDecimal("45.50"), null, null, "ana");

    assertThat(advance.captured()).isTrue();
    assertThat(card.captured()).isTrue();
    assertThat(card.reference()).startsWith("Aut. ");
    assertThat(card.receiptNo()).isGreaterThan(advance.receiptNo());
    a = cashier.account(stayId);
    assertThat(a.paid()).isEqualByComparingTo("145.50");
    assertThat(a.deposits()).isEqualByComparingTo("100.00");
    assertThat(a.due()).isEqualByComparingTo("200.00");
    assertThat(receipts.receipt(card.id())).get().satisfies(pdf -> assertThat(pdf.bytes()).startsWith("%PDF".getBytes()));
    assertThat(receipts.proforma(stayId)).get().satisfies(pdf -> assertThat(pdf.fileName()).isEqualTo("proforma-" + stayId + ".pdf"));
  }

  @Test
  void theTerminalDeclinesTheDemosCard_andAPayLinkWaitsForTheGuest() {
    var stayId = inHouse();

    var declined = cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.CARD_PINPAD, new BigDecimal("45.99"), null, null, "ana");
    assertThat(declined.status()).isEqualTo(Payment.Status.DECLINED);
    assertThat(declined.receiptNo()).isNull();

    var link = cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.PAY_LINK, new BigDecimal("200"), null, null, "ana");
    assertThat(link.status()).isEqualTo(Payment.Status.PENDING);
    assertThat(link.email()).startsWith("marta");
    assertThat(cashier.account(stayId).due()).isEqualByComparingTo("345.50");

    var paid = cashier.payLink(link.linkToken()).orElseThrow();
    assertThat(paid.captured()).isTrue();
    assertThat(paid.receiptNo()).isNotNull();
    assertThat(cashier.account(stayId).due()).isEqualByComparingTo("145.50");
    // paid twice is paid once
    assertThat(cashier.payLink(link.linkToken()).orElseThrow().receiptNo()).isEqualTo(paid.receiptNo());

    var refund = cashier.cancel(paid.id(), "ana");
    assertThat(refund.status()).isEqualTo(Payment.Status.CANCELLED);
    assertThat(cashier.account(stayId).due()).isEqualByComparingTo("345.50");
  }

  @Test
  void aCancelledCreditTakesNoChargeOnTheRoom_untilRestored_andTheLimitCanBeSet() {
    var stayId = inHouse();

    cashier.setLimit(stayId, new BigDecimal("500"), "ana");
    assertThat(cashier.account(stayId).creditLimit()).isEqualByComparingTo("500.00");
    assertThat(cashier.account(stayId).overLimit()).isFalse();

    cashier.cancelCredit(stayId, "tarjeta rechazada", "ana");
    var a = cashier.account(stayId);
    assertThat(a.creditCancelled()).isTrue();
    assertThat(a.creditLimit()).isEqualByComparingTo("0");
    assertThatThrownBy(() -> folioService.postCharge(stayId, "MB-02", "ana"))
        .isInstanceOf(Cashier.Refused.class).hasMessageContaining("Crédito cancelado");

    cashier.restoreCredit(stayId, "ana");
    assertThat(cashier.account(stayId).creditLimit()).isEqualByComparingTo("500.00");
    assertThat(folioService.postCharge(stayId, "MB-02", "ana")).isPresent();
  }

  List<String> events(String stayId) {
    return outbox.all(io.mateu.ecdemo1.frontoffice.infra.outbox.CommandOutbox.FRONT_OFFICE_EVENTS).stream()
        .filter(e -> e.key().equals("MRU01/" + stayId)).map(io.mateu.ecdemo1.messaging.OutboxMessage::payload).toList();
  }

  @Test
  void whatTheTillCapturesGoesToOperasFolio_aRefundToo_andOperasAnswerIsShown() {
    var stayId = inHouse();

    var cash = cashier.take(stayId, Payment.Kind.DEPOSIT, Payment.Method.CASH, new BigDecimal("80"), null, null, "ana");
    cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.CARD_PINPAD, new BigDecimal("10.99"), null, null, "ana");
    cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.PAY_LINK, new BigDecimal("50"), null, null, "ana");

    // only what was captured: not the declined card, not the link nobody paid yet
    assertThat(events(stayId)).singleElement().satisfies(e -> assertThat(e).contains("\"payment-taken\"")
        .contains("\"paymentId\":\"" + cash.id() + "\"").contains("\"method\":\"CASH\"").contains("\"kind\":\"DEPOSIT\""));
    assertThat(postings.of("PAY:" + cash.id())).get().satisfies(p -> assertThat(p.state()).contains("cobro enviado"));

    pms.take(new io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.RecordCharge("RC-" + cash.id(), "XMAR",
        "39480001", stayId, "PAY:" + cash.id(), false, false, "En el folio de Opera", "88776655"));
    assertThat(postings.of("PAY:" + cash.id())).get().satisfies(p -> assertThat(p.state()).isEqualTo("Opera: en el folio · 88776655"));

    cashier.cancel(cash.id(), "ana");
    assertThat(events(stayId)).hasSize(2).last().satisfies(e -> assertThat(e).contains("\"payment-refunded\""));
  }

  @Test
  void aLinkPaidTwiceAtOnceIsCapturedOnce_andToldToThePmsOnce() throws Exception {
    var stayId = inHouse();
    var link = cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.PAY_LINK, new BigDecimal("200"), null, null, "ana");

    // Two POSTs of the public page racing: both read PENDING before either writes.
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> { start.await(); return cashier.payLink(link.linkToken()).orElseThrow(); });
      var b = pool.submit(() -> { start.await(); return cashier.payLink(link.linkToken()).orElseThrow(); });
      start.countDown();
      assertThat(a.get().receiptNo()).isEqualTo(b.get().receiptNo());
    } finally {
      pool.shutdown();
    }

    assertThat(cashier.account(stayId).paid()).isEqualByComparingTo("200.00");
    assertThat(events(stayId)).singleElement().satisfies(e -> assertThat(e).contains("\"payment-taken\""));
  }

  @Test
  void aLinkPaidMeanwhileIsNotCancelledOverThePayment() {
    var stayId = inHouse();
    var link = cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.PAY_LINK, new BigDecimal("200"), null, null, "ana");
    var cancelled = link.withStatus(Payment.Status.CANCELLED, null, null, null);
    cashier.payLink(link.linkToken());

    // The desk's cancel read PENDING before the guest paid: its transition no longer matches.
    assertThat(cashier.payments.transition(link, cancelled)).isFalse();
    assertThat(cashier.payment(link.id())).get().satisfies(p -> assertThat(p.captured()).isTrue());
  }

  @Test
  void nothingIsTakenWithoutAnAmount_norALinkWithoutAnEmail() {
    var stayId = inHouse();
    assertThatThrownBy(() -> cashier.take(stayId, Payment.Kind.PAYMENT, Payment.Method.CASH, BigDecimal.ZERO, null, null, "ana"))
        .isInstanceOf(Cashier.Refused.class);
    guests.save(Guest.fromReservation("C-NOMAIL", "Sin Email", null, null, null));
    stays.save(Stay.fromReservation("CJ-NOMAIL", "C-NOMAIL", "Doble", "Desayuno", LocalDate.now(), LocalDate.now().plusDays(1),
        1, "TUI", new BigDecimal("100.00"), List.of()));
    assertThatThrownBy(() -> cashier.take("CJ-NOMAIL", Payment.Kind.PAYMENT, Payment.Method.PAY_LINK, BigDecimal.TEN, null,
        null, "ana")).isInstanceOf(Cashier.Refused.class).hasMessageContaining("email");
  }
}
