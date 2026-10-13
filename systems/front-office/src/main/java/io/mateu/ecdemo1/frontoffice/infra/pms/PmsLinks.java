package io.mateu.ecdemo1.frontoffice.infra.pms;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Which PMS reservation each stay is, and the PMS's version of it last written here — columns of the
 * {@code stay} table the Stay aggregate does not map, so nothing the desk does touches them.
 */
@Repository
public class PmsLinks {

  public record Link(String stayId, String pmsReservationId, String pmsVersion) {}

  final JdbcTemplate jdbc;

  public PmsLinks(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The stay a PMS reservation is, if the front office has it. */
  public Optional<Link> byPmsReservation(String pmsReservationId) {
    return jdbc.query("select id, pms_reservation_id, pms_version from stay where pms_reservation_id = ?",
        (rs, n) -> new Link(rs.getString("id"), rs.getString("pms_reservation_id"), rs.getString("pms_version")),
        pmsReservationId).stream().findFirst();
  }

  public Optional<Link> ofStay(String stayId) {
    return jdbc.query("select id, pms_reservation_id, pms_version from stay where id = ?",
        (rs, n) -> new Link(rs.getString("id"), rs.getString("pms_reservation_id"), rs.getString("pms_version")),
        stayId).stream().findFirst();
  }

  /**
   * Where the stay stands in the PMS, in the desk's words — «Opera: en casa», «Opera: pendiente —
   * check-in enviado», «Opera: rechazado — motivo»: what the reception did, as the PMS took it.
   */
  public void state(String stayId, String state) {
    jdbc.update("update stay set pms_state = ? where id = ?", state == null ? null
        : state.length() > 500 ? state.substring(0, 497) + "…" : state, stayId);
  }

  public Optional<String> stateOf(String stayId) {
    return jdbc.query("select pms_state from stay where id = ?", (rs, n) -> rs.getString(1), stayId).stream()
        .filter(java.util.Objects::nonNull).findFirst();
  }

  /** The stay is this PMS reservation, as of this version; the rate plan in words, when known. */
  public void link(String stayId, String pmsReservationId, String pmsVersion, String ratePlan) {
    jdbc.update("update stay set pms_reservation_id = ?, pms_version = ?, rate_plan = ? where id = ?",
        pmsReservationId, pmsVersion, ratePlan, stayId);
  }

  /** The price the CRS agreed for the stay (the rate the PMS keeps fixed); null when unknown. */
  public void agreed(String stayId, java.math.BigDecimal agreedTotal) {
    jdbc.update("update stay set agreed_total = ? where id = ?", agreedTotal, stayId);
  }

  /** The company billed for the stay: a partner of the ERP, by code, and its name. */
  public record Company(String code, String name) {}

  public Optional<Company> companyOf(String stayId) {
    return jdbc.query("select company_code, company_name from stay where id = ?",
        (rs, n) -> new Company(rs.getString(1), rs.getString(2)), stayId).stream()
        .filter(c -> c.name() != null || c.code() != null).findFirst();
  }

  /** The company the desk set (null: none): kept until Opera's reservation shows it ({@link #companyFromPms}). */
  public void companySetAtDesk(String stayId, String code, String name) {
    jdbc.update("update stay set company_code = ?, company_name = ?, company_pending = true where id = ?",
        code, name, stayId);
  }

  /**
   * The company on the PMS's reservation, as a projection of it brings it. While the desk's choice has
   * not reached the PMS, the reservation as it was does not undo it; once the PMS shows it, the PMS's is
   * the stay's again.
   */
  public void companyFromPms(String stayId, String code, String name) {
    var pending = Boolean.TRUE.equals(jdbc.query("select company_pending from stay where id = ?",
        (rs, n) -> rs.getBoolean(1), stayId).stream().findFirst().orElse(false));
    if (pending) {
      var desk = companyOf(stayId).orElse(new Company(null, null));
      if (!java.util.Objects.equals(blankToNull(desk.code()), blankToNull(code))) {
        return;
      }
    }
    jdbc.update("update stay set company_code = ?, company_name = ?, company_pending = false where id = ?",
        code, name, stayId);
  }

  /** The company's name, corrected at the desk (its master has the new one). */
  public void companyName(String stayId, String name) {
    jdbc.update("update stay set company_name = ? where id = ?", name, stayId);
  }

  static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  public Optional<java.math.BigDecimal> agreedOf(String stayId) {
    return jdbc.query("select agreed_total from stay where id = ?", (rs, n) -> rs.getBigDecimal(1), stayId).stream()
        .filter(java.util.Objects::nonNull).findFirst();
  }
}
