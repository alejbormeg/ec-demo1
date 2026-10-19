package io.mateu.ecdemo1.frontoffice.infra.persistence;

import io.mateu.ecdemo1.frontoffice.domain.cashier.CreditTerms;
import io.mateu.ecdemo1.frontoffice.domain.cashier.Payment;
import io.mateu.ecdemo1.frontoffice.domain.cashier.Payments;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The desk's cashiering: payments ({@code folio_payment}) and credit terms ({@code folio_credit}). Plain SQL. */
@Repository
class JdbcCashier implements Payments, CreditTerms.Repository {

  final JdbcTemplate jdbc;

  JdbcCashier(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Payment save(Payment p) {
    jdbc.update("delete from folio_payment where id = ?", p.id());
    jdbc.update("insert into folio_payment (id, stay_id, kind, method, amount, currency, status, reference, link_token, "
            + "email, receipt_no, created_at, created_by, captured_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        p.id(), p.stayId(), p.kind().name(), p.method().name(), p.amount(), p.currency(), p.status().name(), p.reference(),
        p.linkToken(), p.email(), p.receiptNo(), ts(p.createdAt()), p.createdBy(), ts(p.capturedAt()));
    return p;
  }

  @Override
  public boolean transition(Payment from, Payment to) {
    // One conditional statement, so the database decides the race: the second of two concurrent
    // updates waits for the first's row lock and then no longer matches the status it expected.
    return jdbc.update("update folio_payment set status = ?, reference = ?, receipt_no = ?, captured_at = ? "
            + "where id = ? and status = ?", to.status().name(), to.reference(), to.receiptNo(), ts(to.capturedAt()),
        from.id(), from.status().name()) == 1;
  }

  @Override
  public Optional<Payment> byId(String id) {
    return jdbc.query("select * from folio_payment where id = ?", (rs, n) -> payment(rs), id).stream().findFirst();
  }

  @Override
  public Optional<Payment> byLinkToken(String token) {
    return jdbc.query("select * from folio_payment where link_token = ?", (rs, n) -> payment(rs), token).stream()
        .findFirst();
  }

  @Override
  public List<Payment> of(String stayId) {
    return jdbc.query("select * from folio_payment where stay_id = ? order by created_at, id", (rs, n) -> payment(rs),
        stayId);
  }

  @Override
  public int nextReceiptNo() {
    var max = jdbc.queryForObject("select max(receipt_no) from folio_payment", Integer.class);
    return max == null ? 1 : max + 1;
  }

  static Payment payment(ResultSet rs) throws SQLException {
    var receipt = rs.getObject("receipt_no") instanceof Number n ? n.intValue() : null;
    return new Payment(rs.getString("id"), rs.getString("stay_id"), Payment.Kind.valueOf(rs.getString("kind")),
        Payment.Method.valueOf(rs.getString("method")), rs.getBigDecimal("amount"), rs.getString("currency"),
        Payment.Status.valueOf(rs.getString("status")), rs.getString("reference"), rs.getString("link_token"),
        rs.getString("email"), receipt, instant(rs.getTimestamp("created_at")), rs.getString("created_by"),
        instant(rs.getTimestamp("captured_at")));
  }

  @Override
  public Optional<CreditTerms> termsOf(String stayId) {
    return jdbc.query("select * from folio_credit where stay_id = ?", (rs, n) -> new CreditTerms(rs.getString("stay_id"),
        rs.getBigDecimal("credit_limit"), rs.getBoolean("cancelled"), rs.getString("reason"), rs.getString("changed_by"),
        instant(rs.getTimestamp("changed_at"))), stayId).stream().findFirst();
  }

  @Override
  public void save(CreditTerms t) {
    jdbc.update("delete from folio_credit where stay_id = ?", t.stayId());
    jdbc.update("insert into folio_credit (stay_id, credit_limit, cancelled, reason, changed_by, changed_at) "
        + "values (?, ?, ?, ?, ?, ?)", t.stayId(), t.limit(), t.cancelled(), t.reason(), t.changedBy(), ts(t.changedAt()));
  }

  static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
