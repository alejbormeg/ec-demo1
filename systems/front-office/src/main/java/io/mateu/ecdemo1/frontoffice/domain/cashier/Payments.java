package io.mateu.ecdemo1.frontoffice.domain.cashier;

import java.util.List;
import java.util.Optional;

/** The payments taken on stays' accounts. */
public interface Payments {

  Payment save(Payment payment);

  /**
   * Moves a payment to {@code to}'s status, receipt and capture — only if it is still in {@code from}'s
   * status. Whether it was: of two requests racing for the same transition (a link paid twice at once, a
   * link paid while the desk cancels it), exactly one sees true, and only that one may go on to capture or
   * tell the PMS.
   */
  boolean transition(Payment from, Payment to);

  Optional<Payment> byId(String id);

  Optional<Payment> byLinkToken(String token);

  /** A stay's payments, oldest first. */
  List<Payment> of(String stayId);

  /** The next receipt number of the hotel's desk: one sequence for every receipt. */
  int nextReceiptNo();
}
