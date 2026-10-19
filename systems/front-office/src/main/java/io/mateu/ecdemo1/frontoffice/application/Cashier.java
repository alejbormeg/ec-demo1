package io.mateu.ecdemo1.frontoffice.application;

import io.mateu.ecdemo1.frontoffice.domain.cashier.CreditTerms;
import io.mateu.ecdemo1.frontoffice.domain.cashier.Payment;
import io.mateu.ecdemo1.frontoffice.domain.cashier.Payments;
import io.mateu.ecdemo1.frontoffice.domain.folio.Folio;
import io.mateu.ecdemo1.frontoffice.domain.folio.FolioRepository;
import io.mateu.ecdemo1.frontoffice.domain.guest.GuestRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The desk's cashiering on a stay's account (OPERA's Billing / Cashiering): what is owed, in the hotel's
 * currency; the payments and advances taken — cash, the card terminal, a payment link by email, a
 * transfer, a manual entry —, each with its receipt; the credit limit and whether the credit was
 * cancelled.
 *
 * <p>The account is the folio's charges less what was captured. The card terminal and the payment link are
 * simulated (no payment provider in the PoC): the terminal approves, except an amount ending in .99 —
 * the demo's declined card —, and the link is paid from its page. Opera still settles its own folio at
 * the check-out with what is due there.
 */
@Service
// a service, never part of a page's state (Mateu serialises the pages, and the services some of them hold)
@com.fasterxml.jackson.annotation.JsonIgnoreType
public class Cashier {

  /** The demo terminal declines amounts with these cents: the declined card of the demo. */
  static final int DECLINED_CENTS = 99;

  final StayRepository stays;
  final GuestRepository guests;
  final FolioRepository folios;
  final Payments payments;
  final CreditTerms.Repository credit;
  final StayAudit audit;
  final String currency;
  final TransactionTemplate transaction;
  final SecureRandom random = new SecureRandom();
  final Clock clock = Clock.systemUTC();

  /**
   * Where the PMS hears of the till's payments; none in a test that does not wire it. A final holder, set
   * once: pages hold this service, and Mateu walks a page's non-final fields when it writes its state.
   */
  private final java.util.concurrent.atomic.AtomicReference<io.mateu.ecdemo1.frontoffice.infra.pms.ReceptionReports> reports =
      new java.util.concurrent.atomic.AtomicReference<>();

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setReports(io.mateu.ecdemo1.frontoffice.infra.pms.ReceptionReports reports) {
    this.reports.set(reports);
  }

  void toThePms(Payment p, boolean refund, String by) {
    var r = reports.get();
    if (r == null) {
      return;
    }
    if (refund) {
      r.paymentRefunded(p.stayId(), p, by);
    } else {
      r.paymentTaken(p.stayId(), p, by);
    }
  }

  public Cashier(StayRepository stays, GuestRepository guests, FolioRepository folios, Payments payments,
                 CreditTerms.Repository credit, StayAudit audit, @Value("${frontoffice.currency:}") String currency,
                 PlatformTransactionManager transactions) {
    this.stays = stays;
    this.guests = guests;
    this.folios = folios;
    this.payments = payments;
    this.credit = credit;
    this.audit = audit;
    this.currency = currency == null || currency.isBlank() ? "EUR" : currency.trim().toUpperCase();
    this.transaction = new TransactionTemplate(transactions);
  }

  /** Why the desk cannot do something with the account, in words for it. */
  public static class Refused extends RuntimeException {
    public Refused(String message) {
      super(message);
    }
  }

  /**
   * A stay's account as the desk sees it.
   *
   * @param charges     what the folio's charges add up to
   * @param paid        what was captured (payments and advances)
   * @param due         what is still owed — the outstanding balance; negative, in favour of the guest
   * @param creditLimit the limit in force: zero when the credit is cancelled; the pre-authorization when none was set
   * @param overLimit   whether the charges go past the limit
   */
  public record Account(String stayId, String currency, BigDecimal charges, BigDecimal paid, BigDecimal deposits,
                        BigDecimal due, BigDecimal creditLimit, boolean limitSet, boolean creditCancelled,
                        String creditReason, boolean overLimit, List<Payment> payments) {}

  public Account account(String stayId) {
    var stay = stay(stayId);
    var folio = folios.findByStayId(stayId).orElse(null);
    var charges = folio == null ? BigDecimal.ZERO : folio.balance();
    var all = payments.of(stayId);
    var paid = sum(all.stream().filter(Payment::captured).toList());
    var deposits = sum(all.stream().filter(p -> p.captured() && p.kind() == Payment.Kind.DEPOSIT).toList());
    var terms = credit.termsOf(stayId).orElse(null);
    var cancelled = terms != null && terms.cancelled();
    var limit = cancelled ? BigDecimal.ZERO
        : terms != null && terms.limit() != null ? terms.limit()
        : folio != null && folio.preauthorized() != null ? folio.preauthorized() : stay.total();
    var due = charges.subtract(paid);
    return new Account(stayId, currency, scale(charges), scale(paid), scale(deposits), scale(due), scale(limit),
        terms != null && terms.limit() != null, cancelled, terms == null ? null : terms.reason(),
        due.compareTo(limit == null ? BigDecimal.ZERO : limit) > 0, all);
  }

  /** Whether a charge of this amount may go on the room's account: not with the credit cancelled. */
  public void checkCharge(String stayId, BigDecimal amount) {
    var terms = credit.termsOf(stayId).orElse(null);
    if (terms != null && terms.cancelled()) {
      throw new Refused("Crédito cancelado" + (terms.reason() == null ? "" : " (" + terms.reason() + ")")
          + ": no se carga nada a la habitación sin cobrarlo al momento");
    }
  }

  /**
   * Takes a payment, or an advance, at the desk. Cash, transfer and manual: captured. The card terminal:
   * approved or declined (simulated). A payment link: pending until the guest pays it, sent to {@code email}
   * (the guest's, if none).
   */
  public Payment take(String stayId, Payment.Kind kind, Payment.Method method, BigDecimal amount, String email,
                      String reference, String by) {
    if (amount == null || amount.signum() <= 0) {
      throw new Refused("El importe tiene que ser mayor que cero");
    }
    if (kind == null || method == null) {
      throw new Refused("Elige qué es (cobro o anticipo) y cómo se paga");
    }
    var stay = stay(stayId);
    var to = method == Payment.Method.PAY_LINK
        ? Optional.ofNullable(blank(email) ? guests.findById(stay.guestId()).map(g -> g.email()).orElse(null) : email.trim())
            .filter(e -> e.contains("@")).orElseThrow(() -> new Refused("El link de pago necesita un email"))
        : null;
    var value = scale(amount);
    return audit.run("Payment taken", stayId, by, StayAudit.params("kind", kind, "method", method, "amount", value),
        () -> transaction.execute(s -> {
          var now = clock.instant();
          var payment = new Payment(UUID.randomUUID().toString(), stayId, kind, method, value, currency,
              Payment.Status.PENDING, blank(reference) ? null : reference.trim(),
              method == Payment.Method.PAY_LINK ? token() : null, to, null, now,
              by, null);
          payment = switch (method) {
            case PAY_LINK -> payment;
            case CARD_PINPAD -> value.remainder(BigDecimal.ONE).movePointRight(2).intValue() == DECLINED_CENTS
                ? payment.withStatus(Payment.Status.DECLINED, "Denegada por el datáfono (05 · no autorizada)", null, null)
                : payment.withStatus(Payment.Status.CAPTURED, "Aut. " + authorization(), payments.nextReceiptNo(), now);
            default -> payment.withStatus(Payment.Status.CAPTURED, null, payments.nextReceiptNo(), now);
          };
          var saved = payments.save(payment);
          if (saved.captured()) {
            toThePms(saved, false, by);
          }
          return saved;
        }), Cashier::said);
  }

  /**
   * The guest paid the link (its page): captured, with its receipt. A link paid or cancelled stays as it is.
   *
   * <p>The page is public and a double click is two POSTs: the PENDING read above is only a shortcut, the
   * conditional transition is what decides. The request that loses it captures nothing and tells the PMS
   * nothing; it answers with the payment as the winner left it.
   */
  public Optional<Payment> payLink(String token) {
    var payment = blank(token) ? Optional.<Payment>empty() : payments.byLinkToken(token);
    if (payment.isEmpty() || payment.get().status() != Payment.Status.PENDING) {
      return payment;
    }
    var p = payment.get();
    return Optional.of(audit.run("Payment link paid", p.stayId(), "huésped (link)", StayAudit.params("amount", p.amount()),
        () -> transaction.execute(s -> {
          var paid = p.withStatus(Payment.Status.CAPTURED, "Pagado por link · aut. " + authorization(),
              payments.nextReceiptNo(), clock.instant());
          if (!payments.transition(p, paid)) {
            return payments.byId(p.id()).orElse(p);
          }
          toThePms(paid, false, "huésped (link)");
          return paid;
        }), Cashier::said));
  }

  public Optional<Payment> byLinkToken(String token) {
    return blank(token) ? Optional.empty() : payments.byLinkToken(token);
  }

  public Optional<Payment> payment(String id) {
    return payments.byId(id);
  }

  /**
   * Cancels a pending link, or takes a captured payment back (a refund): it stays, and counts for nothing.
   *
   * <p>Only from the status read here: if the guest paid the link meanwhile, or another desk already took
   * it back, the transition fails and the desk is told — never a link cancelled over a payment the PMS
   * already has, nor a refund posted twice.
   */
  public Payment cancel(String paymentId, String by) {
    var p = payments.byId(paymentId).orElseThrow(() -> new Refused("No hay ningún cobro " + paymentId));
    if (p.status() == Payment.Status.CANCELLED || p.status() == Payment.Status.DECLINED) {
      return p;
    }
    return audit.run("Payment cancelled", p.stayId(), by, StayAudit.params("payment", p.id(), "amount", p.amount()),
        () -> transaction.execute(s -> {
          var cancelled = p.withStatus(Payment.Status.CANCELLED, null, null, null);
          if (!payments.transition(p, cancelled)) {
            throw new Refused("El cobro ha cambiado mientras tanto: vuelve a abrirlo antes de anularlo");
          }
          if (p.captured()) {
            toThePms(cancelled, true, by);
          }
          return cancelled;
        }),
        x -> (p.captured() ? "Cobro devuelto: " : "Link anulado: ") + x.amount() + " " + x.currency());
  }

  /** The credit limit of the stay; null puts back the pre-authorization's. */
  public void setLimit(String stayId, BigDecimal limit, String by) {
    if (limit != null && limit.signum() < 0) {
      throw new Refused("El límite de crédito no puede ser negativo");
    }
    stay(stayId);
    audit.run("Credit limit set", stayId, by, StayAudit.params("limit", limit), () -> {
      transaction.executeWithoutResult(s -> {
        var terms = credit.termsOf(stayId).orElse(null);
        credit.save(new CreditTerms(stayId, limit == null ? null : scale(limit), terms != null && terms.cancelled(),
            terms == null ? null : terms.reason(), by, clock.instant()));
      });
      return limit;
    }, l -> l == null ? "Límite de crédito: el de la preautorización" : "Límite de crédito: " + scale(l) + " " + currency);
  }

  /** «Crédito cancelado»: the limit is zero — nothing on the room's account without paying it — until restored. */
  public void cancelCredit(String stayId, String reason, String by) {
    stay(stayId);
    audit.run("Credit cancelled", stayId, by, StayAudit.params("reason", reason), () -> {
      transaction.executeWithoutResult(s -> {
        var terms = credit.termsOf(stayId).orElse(null);
        credit.save(new CreditTerms(stayId, terms == null ? null : terms.limit(), true, blank(reason) ? null : reason.trim(),
            by, clock.instant()));
      });
      return true;
    }, ok -> "Crédito cancelado: la habitación ya no admite cargos sin cobrarlos");
  }

  public void restoreCredit(String stayId, String by) {
    stay(stayId);
    audit.run("Credit restored", stayId, by, StayAudit.params(), () -> {
      transaction.executeWithoutResult(s -> {
        var terms = credit.termsOf(stayId).orElse(null);
        credit.save(new CreditTerms(stayId, terms == null ? null : terms.limit(), false, null, by, clock.instant()));
      });
      return true;
    }, ok -> "Crédito restablecido");
  }

  static String said(Payment p) {
    return switch (p.status()) {
      case CAPTURED -> p.kind().label + " de " + p.amount() + " " + p.currency() + " (" + p.method().label + "), recibo nº "
          + p.receiptNo();
      case PENDING -> "Link de pago de " + p.amount() + " " + p.currency() + " enviado a " + p.email();
      case DECLINED -> "Tarjeta denegada: " + p.amount() + " " + p.currency() + " sin cobrar";
      case CANCELLED -> "Anulado";
    };
  }

  String token() {
    var bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  String authorization() {
    return String.format("%06d", random.nextInt(1_000_000));
  }

  static BigDecimal sum(List<Payment> list) {
    return list.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  static BigDecimal scale(BigDecimal amount) {
    return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
  }

  static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  Stay stay(String stayId) {
    return stays.findById(stayId).orElseThrow(() -> new NoSuchElementException("Reserva " + stayId + " no encontrada"));
  }

  /** The folio of the stay, if one is open: what the receipts and the proforma print. */
  public Optional<Folio> folio(String stayId) {
    return folios.findByStayId(stayId);
  }
}
