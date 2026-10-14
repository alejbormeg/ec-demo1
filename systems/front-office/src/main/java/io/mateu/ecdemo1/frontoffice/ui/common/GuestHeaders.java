package io.mateu.ecdemo1.frontoffice.ui.common;

import io.mateu.ecdemo1.frontoffice.domain.folio.Folio;
import io.mateu.ecdemo1.frontoffice.domain.guest.Guest;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.uidl.data.Chip;
import io.mateu.uidl.data.EntityHeader;
import io.mateu.uidl.data.Fact;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared {@link EntityHeader} builders — the persistent guest context banner of every flow. */
public final class GuestHeaders {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM");

  private GuestHeaders() {}

  /** The arriving guest's banner, shown as the first component of every check-in wizard step. */
  public static Component arrivalHeader(String stayId) {
    return arrivalHeader(stayId, false);
  }

  /** The check-in's banner: the arrival's (the way back to the reservation is in the wizard's buttons). */
  public static Component checkInHeader(String stayId) {
    return arrivalHeader(stayId, false);
  }

  /** The same; {@code companyEditable}: EMPRESA carries the button that chooses or corrects it (opEmpresa). */
  public static Component arrivalHeader(String stayId, boolean companyEditable) {
    var view = FrontOffice.stayView(stayId);
    var stay = view.stay();
    var guest = view.guest();
    var known = FrontOffice.recognition(stayId, 1);
    var badges = new ArrayList<Chip>();
    badges.add(Tiers.chip(tier(guest, known)));
    walkIn(stay).ifPresent(badges::add);
    if (companyEditable) {
      // Redwood's page header shows four facts, the metric among them: with the reservation page's four
      // (the amount, AGENCIA, EMPRESA, CHECK-IN) the points ride with the tier, after the subtitle
      badges.add(Chip.builder().label(points(guest, known) + " puntos").color("contrast").build());
    }
    return EntityHeader.builder()
        .title(FrontOffice.withFlag(stayId, 1, guest.id(), guest.name()))
        .badges(badges)
        .subtitle(staySubtitle(stay))
        .facts(totalFacts(stay.total(), FrontOffice.agreedPrice(stayId).orElse(null), stay.agency(),
            FrontOffice.company(stayId).orElse(null), companyEditable,
            companyEditable ? (FrontOffice.ops(stayId).firma() ? "Registro firmado" : "Sin firmar") : null))
        .metricLabel("FIDELIDAD")
        .metricValue(points(guest, known))
        .metricCaption(staysCaption(guest, known))
        .style("width: 100%;")
        .build();
  }

  /**
   * What the stay costs. The CRS's price is the one agreed with the guest — the rate Opera keeps fixed —;
   * Opera's total adds the packages it posts apart from the rate (XMAR's breakfast, BRKFST), and that is
   * what Opera bills and the folio, the preauthorization and the invoice follow. Equal (or the CRS's
   * unknown, born in Opera): one «TOTAL RESERVA», as before; different: both, the CRS's first, and by
   * how much Opera's goes over.
   */
  static List<Fact> totalFacts(BigDecimal total, BigDecimal agreed, String agency) {
    return totalFacts(total, agreed, agency, null, false);
  }

  static List<Fact> totalFacts(BigDecimal total, BigDecimal agreed, String agency, String company) {
    return totalFacts(total, agreed, agency, company, false);
  }

  /** The same, and the company billed when neither the agency nor the guest is — «—» without one. */
  static List<Fact> totalFacts(BigDecimal total, BigDecimal agreed, String agency, String company,
                               boolean companyEditable) {
    return totalFacts(total, agreed, agency, company, companyEditable, null);
  }

  /**
   * The same, for the reservation's page ({@code actions}): the amount carries the till's button
   * (abrirCaja), EMPRESA the company's (opEmpresa), and CHECK-IN — {@code checkIn}, the registration's
   * state; null: no such fact — the lobby tablet's (enviarTableta). Four facts at most, as Redwood's page
   * header shows no more: the CRS's price and Opera's go in one when they differ.
   */
  static List<Fact> totalFacts(BigDecimal total, BigDecimal agreed, String agency, String company,
                               boolean actions, String checkIn) {
    var facts = new ArrayList<Fact>();
    Fact.FactBuilder amount;
    if (agreed == null || total == null || agreed.compareTo(total) == 0) {
      amount = Fact.builder().label("TOTAL RESERVA").value(euros(total));
    } else {
      var over = total.subtract(agreed);
      amount = Fact.builder().label("PRECIO CRS / OPERA").value(euros(agreed) + " / " + euros(total) + " ("
          + (over.signum() > 0 ? "+" : "−") + euros(over.abs()) + " paquetes de Opera)");
    }
    if (actions) {
      amount.actionId("abrirCaja").icon("vaadin:money").actionLabel("Caja");
    }
    facts.add(amount.build());
    facts.add(Fact.builder().label("AGENCIA").value(agency).build());
    facts.add(companyFact(company, actions));
    if (checkIn != null) {
      var fact = Fact.builder().label("CHECK-IN").value(checkIn);
      if (actions) {
        // Oracle's own tablet icon: Redwood takes oj-ux-ico-* as they come (Mateu maps no vaadin:tablet)
        fact.actionId("enviarTableta").icon("oj-ux-ico-tablet-portrait").actionLabel("Check-in en tableta");
      }
      facts.add(fact.build());
    }
    return facts;
  }


  /** EMPRESA, «—» without one; {@code editable}: with the ✎ that opens its drawer (opEmpresa). */
  static Fact companyFact(String company, boolean editable) {
    var fact = Fact.builder().label("EMPRESA").value(company == null || company.isBlank() ? "—" : company);
    if (editable) {
      fact.actionId("opEmpresa").actionLabel(company == null || company.isBlank() ? "Elegir la empresa"
          : "Cambiar o corregir la empresa");
    }
    return fact.build();
  }

  /** The departing guest's banner at check-out. */
  public static Component departureHeader(String stayId) {
    var view = FrontOffice.stayView(stayId);
    var stay = view.stay();
    var guest = view.guest();
    // no folio facts here — the check-out screen shows the breakdown and the preauth below
    return EntityHeader.builder()
        .title(FrontOffice.withFlag(stayId, 1, guest.id(), guest.name()))
        .badges(List.of(Tiers.chip(tier(guest, FrontOffice.recognition(stayId, 1)))))
        .subtitle(
            stay.roomLabel() + " · " + stay.roomType() + " · " + stay.board() + " · "
                + stay.nights() + "N")
        .style("width: 100%;")
        .build();
  }

  /** The in-house guest's 360 banner. */
  public static Component inHouseHeader(String stayId) {
    return inHouseHeader(stayId, false);
  }

  public static Component inHouseHeader(String stayId, boolean companyEditable) {
    var view = FrontOffice.stayView(stayId);
    var stay = view.stay();
    var guest = view.guest();
    var folio = view.folio();
    var known = FrontOffice.recognition(stayId, 1);
    var badges = new ArrayList<Chip>();
    badges.add(Tiers.chip(tier(guest, known)));
    if (stay.wishesTotal() > 0) {
      badges.add(Chip.builder().label(wishes(stay)).color("success").build());
    }
    if (stay.vipNote() != null) {
      badges.add(Chip.builder().label(stay.vipNote()).color("warning").build());
    }
    walkIn(stay).ifPresent(badges::add);
    return EntityHeader.builder()
        .title(FrontOffice.withFlag(stayId, 1, guest.id(), guest.name()))
        .badges(badges)
        .subtitle(
            stay.roomType() + " · " + stay.roomLabel() + " · Sal. "
                + stay.checkOut().format(DAY) + " · " + stay.board())
        .facts(
            List.of(
                companyEditable
                    ? Fact.builder().label("BALANCE").value(euros(balance(folio)))
                        .actionId("abrirCaja").icon("vaadin:money").actionLabel("Caja").build()
                    : Fact.builder().label("BALANCE").value(euros(balance(folio))).build(),
                Fact.builder()
                    .label("PREAUTORIZADO")
                    .value(euros(folio == null ? null : folio.preauthorized()))
                    .build(),
                companyFact(FrontOffice.company(stayId).orElse(null), companyEditable)))
        .metricLabel("FIDELIDAD")
        .metricValue(points(guest, known))
        .metricCaption(staysCaption(guest, known))
        .style("width: 100%;")
        .build();
  }

  /**
   * A walk-in's standing with the CRS: pending while the CRS has not booked it, its locator (and
   * Opera's) once it has, and why not if it refused it.
   */
  public static java.util.Optional<Chip> walkIn(Stay stay) {
    return io.mateu.ecdemo1.frontoffice.infra.crs.WalkInDesk.of(stay.id()).map(w -> Chip.builder()
        .label(w.label())
        .color(switch (w.status()) {
          case PENDING -> "warning";
          case BOOKED -> "success";
          case REFUSED -> "error";
        })
        .build());
  }

  /** "Ocean Suite · 15 Jul → 22 Jul · 7N · 2pax · All Inclusive" */
  public static String staySubtitle(Stay stay) {
    return stay.roomType() + " · " + stayDates(stay) + " · " + stay.nights() + "N · " + stay.pax()
        + "pax · " + stay.board();
  }

  public static String stayDates(Stay stay) {
    return stay.checkIn().format(DAY) + " → " + stay.checkOut().format(DAY);
  }

  public static String wishes(Stay stay) {
    return "Deseos " + stay.wishesGranted() + "/" + stay.wishesTotal();
  }

  /**
   * The holder's tier: Riu Class's, when the desk knows the customer and the loyalty service answers;
   * else the guest's own.
   */
  static io.mateu.ecdemo1.frontoffice.domain.guest.GuestTier tier(Guest guest,
      io.mateu.ecdemo1.frontoffice.application.Recognition.View known) {
    return known.known() ? known.loyalty().map(l -> {
      try {
        return io.mateu.ecdemo1.frontoffice.domain.guest.GuestTier.valueOf(l.tier());
      } catch (RuntimeException e) {
        return guest.tier();
      }
    }).orElse(guest.tier()) : guest.tier();
  }

  /** The holder's points: Riu Class's when it answers for a customer the desk knows, else the guest's own. */
  static String points(Guest guest, io.mateu.ecdemo1.frontoffice.application.Recognition.View known) {
    return known.known() && known.loyalty().isPresent()
        ? CustomerPanels.points(known.loyalty().get().points()) : points(guest);
  }

  /** «N estancias»: the chain's, from the customer history, when the desk knows the customer; else the guest's own. */
  static String staysCaption(Guest guest, io.mateu.ecdemo1.frontoffice.application.Recognition.View known) {
    return known.stays().map(s -> s.stays() + " estancias en la cadena").orElse(guest.stays() + " estancias");
  }

  /** Loyalty points with thousands separator: 48500 → "48.500". */
  public static String points(Guest guest) {
    return String.format(Locale.US, "%,d", guest.loyaltyPoints()).replace(',', '.');
  }

  public static BigDecimal balance(Folio folio) {
    return folio == null ? null : folio.balance();
  }

  public static String euros(BigDecimal amount) {
    return euros(amount == null ? null : amount.doubleValue());
  }

  public static String euros(Double amount) {
    if (amount == null) {
      return "—";
    }
    return "€ "
        + String.format(Locale.US, "%,.2f", amount)
            .replace(',', '_')
            .replace('.', ',')
            .replace('_', '.');
  }

  /** The id is the first route segment under the given mount (e.g. /checkin/&lt;id&gt;). */
  public static String idFromRoute(HttpRequest httpRequest, String mount) {
    var rq = httpRequest.runActionRq();
    if (rq == null || rq.route() == null) {
      return null;
    }
    // embedded islands carry markers as a query string (e.g. ?_embeddedMediator=1) — strip it
    var route = rq.route();
    var q = route.indexOf('?');
    if (q >= 0) {
      route = route.substring(0, q);
    }
    var segs = route.replaceFirst("^/", "").split("/");
    for (int i = 0; i < segs.length; i++) {
      if (mount.equals(segs[i])) {
        return i + 1 < segs.length && !segs[i + 1].isBlank()
            ? io.mateu.ecdemo1.frontoffice.infra.crs.WalkInDesk.stayIdFor(segs[i + 1]) : null;
      }
    }
    return segs.length > 0 && !segs[0].isBlank() ? segs[0] : null;
  }

  /** Today, for grouping the departure queue. */
  public static LocalDate today() {
    return LocalDate.now();
  }
}
