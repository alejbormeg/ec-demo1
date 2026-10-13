package io.mateu.ecdemo1.frontoffice.infra.pms;

import io.mateu.ecdemo1.frontoffice.domain.folio.ChargeKind;
import io.mateu.ecdemo1.frontoffice.domain.folio.FolioLine;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.ecdemo1.frontoffice.domain.stay.WalkIns;
import io.mateu.ecdemo1.frontoffice.infra.outbox.CommandOutbox;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeEvent;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * What the desk did, up to the PMS — the master of the stay (pms-fo). A check-in, a check-out, a
 * reservation nobody came for, a charge put on the stay's folio or taken back: each is an event on
 * {@code front-office-events}, written to the outbox
 * in the transaction of the desk's decision, and the pms-fo integration records it in Opera through
 * the engine. The stay shows where that stands: «Opera: pendiente…» now, and what the PMS answers
 * later — «en casa», «salida registrada», «rechazado — motivo» ({@link PmsLinks#state}).
 *
 * <p>The event names the stay both ways: the CRS's locator when the reservation came from the CRS (a
 * walk-in's once the CRS booked it; none for one born in Opera, {@code OP-…}), and Opera's reservation
 * when the stay is linked to it. A walk-in neither has yet is told once its reservation comes back
 * from Opera ({@link #pendingLink}).
 */
@Service
public class ReceptionReports {

  static final Logger log = LoggerFactory.getLogger(ReceptionReports.class);

  /** The stay's state while the PMS does not have its reservation yet (a walk-in the CRS is booking). */
  public static final String WAITING_FOR_THE_PMS = "Opera: pendiente — la reserva aún no ha llegado a Opera";

  final String hotel;
  final String pmsHotel;
  final String currency;
  final WalkIns walkIns;
  final PmsLinks links;
  final CommandOutbox outbox;
  final ChargePostings postings;
  final io.mateu.ecdemo1.frontoffice.domain.folio.FolioRepository folios;
  final Clock clock = Clock.systemUTC();

  public ReceptionReports(@Value("${frontoffice.hotel:MRU01}") String hotel,
      @Value("${frontoffice.pms-hotel:XMAR}") String pmsHotel, @Value("${frontoffice.currency:}") String currency,
      WalkIns walkIns, PmsLinks links, CommandOutbox outbox, ChargePostings postings,
      io.mateu.ecdemo1.frontoffice.domain.folio.FolioRepository folios) {
    this.hotel = hotel;
    this.pmsHotel = pmsHotel;
    this.currency = currency == null || currency.isBlank() ? null : currency.trim();
    this.walkIns = walkIns;
    this.links = links;
    this.outbox = outbox;
    this.postings = postings;
    this.folios = folios;
  }

  record Refs(String crsLocator, String pmsReservationId) {
    boolean none() {
      return crsLocator == null && pmsReservationId == null;
    }
  }

  Refs refs(String stayId) {
    var walkIn = walkIns.of(stayId).orElse(null);
    String crs;
    if (walkIn != null) {
      crs = walkIn.locator();
    } else {
      crs = stayId.startsWith("OP-") ? null : stayId;
    }
    var pms = links.ofStay(stayId).map(PmsLinks.Link::pmsReservationId).filter(id -> !id.isBlank()).orElse(null);
    if (pms == null && walkIn != null) {
      pms = walkIn.pmsReservationId();
    }
    return new Refs(crs, pms);
  }

  /** The desk checked the stay in: in its transaction, the PMS is asked to record it. */
  public void checkedIn(Stay stay, String by) {
    var refs = refs(stay.id());
    if (refs.none()) {
      links.state(stay.id(), WAITING_FOR_THE_PMS);
      log.info("{}: checked in; neither the CRS nor Opera has its reservation yet — told once it is back", stay.id());
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.GuestCheckedIn("CI-" + UUID.randomUUID(), clock.instant(), hotel, stay.id(),
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), stay.roomNumber(), stay.pax(), by));
    links.state(stay.id(), "Opera: pendiente — check-in enviado");
    log.info("{}: checked in, room {} — to the PMS ({} / {})", stay.id(), stay.roomNumber(), refs.crsLocator(),
        refs.pmsReservationId());
  }

  /** The desk checked the stay out: in its transaction, the PMS is asked to record it. */
  public void checkedOut(Stay stay, String by) {
    var refs = refs(stay.id());
    if (refs.none()) {
      log.info("{}: checked out; its reservation is not in the PMS — nothing to record there", stay.id());
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.GuestCheckedOut("CO-" + UUID.randomUUID(), clock.instant(), hotel, stay.id(),
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), stay.roomNumber(), by));
    links.state(stay.id(), "Opera: pendiente — check-out enviado");
    log.info("{}: checked out — to the PMS ({} / {})", stay.id(), refs.crsLocator(), refs.pmsReservationId());
  }

  /**
   * The stay is closed — its guests left: in the check-out's transaction, what the chain keeps of it
   * (the customer history) goes out as one event that carries everything — dates, room, who slept
   * there, what was spent by kind — so its reader needs no other event, nor their order. Unlike
   * {@link #checkedOut}, it goes out for a stay the PMS does not have too: the history is the chain's,
   * not Opera's; the stay's references are then simply null.
   */
  public void closed(Stay stay, String by) {
    closed(stay, by, java.util.Map.of());
  }

  /**
   * As {@link #closed(Stay, String)}, with the customers the desk recognised for certain, by pax (1 the holder,
   * 2… the companions): the stay is theirs in the history and in Riu Class, not the reservation's code —
   * a provisional one, most of the time, that nothing may have consolidated yet (a guest confirmed by their
   * Riu Class number, with no document scanned, tells the MDM nothing).
   */
  public void closed(Stay stay, String by, java.util.Map<Integer, String> recognised) {
    var refs = refs(stay.id());
    var guests = new java.util.ArrayList<FrontOfficeEvent.StayGuest>();
    guests.add(new FrontOfficeEvent.StayGuest(recognised.getOrDefault(1, stay.guestId()), true));
    var companions = stay.companions();
    for (int i = 0; i < companions.size(); i++) {
      var id = recognised.getOrDefault(i + 2, companions.get(i).companionId());
      // a slot nobody registered ("pax-2") is nobody: the same id in every stay, it would gather strangers' stays
      if (id != null && !id.isBlank() && !id.matches("pax-\\d+")) {
        guests.add(new FrontOfficeEvent.StayGuest(id, false));
      }
    }
    // what the folio says was spent, by kind — the accommodation is the PMS's, and not a spend here
    var byKind = new java.util.EnumMap<FrontOfficeEvent.ChargeKind, java.math.BigDecimal>(FrontOfficeEvent.ChargeKind.class);
    folios.findByStayId(stay.id()).ifPresent(folio -> folio.lines().stream()
        .filter(FolioLine::counts)
        .filter(line -> line.kind() != null && line.kind() != ChargeKind.ACCOMMODATION)
        .forEach(line -> byKind.merge(kind(line.kind()), line.amount(), java.math.BigDecimal::add)));
    var charges = byKind.entrySet().stream()
        .map(e -> new FrontOfficeEvent.ChargeTotal(e.getKey(), e.getValue())).toList();
    var total = charges.stream().map(FrontOfficeEvent.ChargeTotal::amount)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    outbox.appendEvent(new FrontOfficeEvent.StayClosed("SC-" + UUID.randomUUID(), clock.instant(), hotel, stay.id(),
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), stay.checkIn(), stay.checkOut(), (int) stay.nights(),
        stay.roomNumber(), stay.roomType(), stay.board(), guests, charges, total, currency));
    log.info("{}: closed — {} guest(s), {} {} spent — to the customer history", stay.id(), guests.size(), total,
        currency == null ? "" : currency);
  }

  /**
   * Nobody of the stay's reservation came: in the caller's transaction, the PMS is asked to record it;
   * it reports it to the CRS, which applies its fee. What to tell the desk.
   */
  public String noShow(String stayId, int pax, String by) {
    var walkIn = walkIns.of(stayId).orElse(null);
    if (walkIn != null && walkIn.locator() == null) {
      return "Este walk-in aún no está en el CRS: el no show queda solo aquí.";
    }
    var refs = refs(stayId);
    outbox.appendEvent(new FrontOfficeEvent.NoShowReported("NS-" + UUID.randomUUID(), clock.instant(), hotel, stayId,
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), pax, by));
    links.state(stayId, "Opera: pendiente — no show enviado");
    log.info("{}: a no-show — to the PMS, and from it to the CRS ({} / {})", stayId, refs.crsLocator(), refs.pmsReservationId());
    return "Se comunica a Opera, el PMS, que lo anota y lo sube al CRS: si la reserva es suya, la cancela con su cargo "
        + "de no show y la estancia lo mostrará.";
  }

  /**
   * A stay the desk checked in before its reservation was anywhere — a walk-in — now linked to it: its
   * check-in goes up now, and the charges of its folio after it. Nothing for any other stay.
   */
  public void pendingLink(Stay stay) {
    if (stay.inHouse() && WAITING_FOR_THE_PMS.equals(links.stateOf(stay.id()).orElse(null))) {
      checkedIn(stay, "front office " + hotel);
      folios.findByStayId(stay.id()).ifPresent(folio -> folio.toThePms().forEach(line -> {
        chargePosted(stay.id(), line, "front office " + hotel);
        if (line.voided()) {
          chargeVoided(stay.id(), line, "front office " + hotel);
        }
      }));
    }
  }

  /**
   * The desk charged something to the stay's folio — a late check-out, an extra, a consumption: in its
   * transaction, the PMS — the master of the folio — is asked to post it on the reservation's folio.
   * The accommodation is not one (the PMS charges it itself), nor a line from before charges went up.
   */
  public void chargePosted(String stayId, FolioLine line, String by) {
    if (!line.toThePms()) {
      return;
    }
    var refs = refs(stayId);
    if (refs.none()) {
      // A walk-in neither the CRS nor Opera has yet: its charges go up with its check-in (pendingLink).
      postings.pending(stayId, line.id(), "Opera: pendiente — la reserva aún no ha llegado a Opera", clock.instant());
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.ChargePosted("CH-" + UUID.randomUUID(), clock.instant(), hotel, stayId,
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), line.id(), kind(line.kind()), line.code(), line.concept(),
        line.amount(), currency, by));
    postings.pending(stayId, line.id(), "Opera: pendiente — cargo enviado", clock.instant());
    log.info("{}: line {} «{}» {} — to the PMS's folio ({} / {})", stayId, line.id(), line.concept(), line.amount(),
        refs.crsLocator(), refs.pmsReservationId());
  }

  /** The desk took a charge back: in its transaction, the PMS is asked to reverse its posting. */
  public void chargeVoided(String stayId, FolioLine line, String by) {
    if (!line.toThePms()) {
      return;
    }
    var refs = refs(stayId);
    if (refs.none()) {
      postings.pending(stayId, line.id(), "Anulado — la reserva aún no ha llegado a Opera", clock.instant());
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.ChargeVoided("CV-" + UUID.randomUUID(), clock.instant(), hotel, stayId,
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), line.id(), kind(line.kind()), line.code(), line.concept(),
        line.amount(), currency, by));
    postings.pending(stayId, line.id(), "Opera: pendiente — anulación enviada", clock.instant());
    log.info("{}: line {} «{}» voided — to the PMS's folio ({} / {})", stayId, line.id(), line.concept(),
        refs.crsLocator(), refs.pmsReservationId());
  }

  /**
   * The till captured a payment or an advance: in its transaction, the PMS is asked to post it on its folio
   * («registrar-cobro»). Its answer comes back on the line {@code PAY:<paymentId>}.
   */
  public void paymentTaken(String stayId, io.mateu.ecdemo1.frontoffice.domain.cashier.Payment p, String by) {
    var line = "PAY:" + p.id();
    var refs = refs(stayId);
    if (refs.none()) {
      postings.pending(stayId, line, "Opera: pendiente — la reserva aún no ha llegado a Opera", clock.instant());
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.PaymentTaken("PY-" + UUID.randomUUID(), clock.instant(), hotel, stayId,
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), p.id(), p.kind().name(), p.method().name(), p.amount(),
        currency, p.reference(), by));
    postings.pending(stayId, line, "Opera: pendiente — cobro enviado", clock.instant());
    log.info("{}: payment {} of {} ({}) — to the PMS's folio ({} / {})", stayId, p.id(), p.amount(), p.method(),
        refs.crsLocator(), refs.pmsReservationId());
  }

  /**
   * The desk set the stay's company (null: took it away): in its transaction, the PMS is asked to put it
   * on the reservation — its profile, and the stay routed to it. A stay the PMS does not have yet takes
   * it from the front office when it does not: nothing to tell now (the desk's choice stays pending).
   */
  public void companyChanged(String stayId, String companyCode, String companyName, String by) {
    var refs = refs(stayId);
    if (refs.none()) {
      log.info("{}: company {} — the reservation is not in the PMS yet", stayId, companyCode);
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.CompanyChanged("CO-" + UUID.randomUUID(), clock.instant(), hotel, stayId,
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), companyCode, companyName, by));
    log.info("{}: company {} — to the PMS's reservation ({} / {})", stayId, companyCode == null ? "none" : companyCode,
        refs.crsLocator(), refs.pmsReservationId());
  }

  /** The till gave a captured payment back: in its transaction, the PMS is asked to refund it on its folio. */
  public void paymentRefunded(String stayId, io.mateu.ecdemo1.frontoffice.domain.cashier.Payment p, String by) {
    var line = "PAY:" + p.id();
    var refs = refs(stayId);
    if (refs.none()) {
      postings.pending(stayId, line, "Devuelto — la reserva aún no ha llegado a Opera", clock.instant());
      return;
    }
    outbox.appendEvent(new FrontOfficeEvent.PaymentRefunded("PR-" + UUID.randomUUID(), clock.instant(), hotel, stayId,
        refs.crsLocator(), pmsHotel, refs.pmsReservationId(), p.id(), p.kind().name(), p.method().name(), p.amount(),
        currency, p.reference(), by));
    postings.pending(stayId, line, "Opera: pendiente — devolución enviada", clock.instant());
    log.info("{}: payment {} refunded — to the PMS's folio ({} / {})", stayId, p.id(), refs.crsLocator(),
        refs.pmsReservationId());
  }

  static FrontOfficeEvent.ChargeKind kind(ChargeKind kind) {
    return switch (kind) {
      case ADD_ON -> FrontOfficeEvent.ChargeKind.ADD_ON;
      case LATE_CHECK_OUT -> FrontOfficeEvent.ChargeKind.LATE_CHECK_OUT;
      case CONSUMPTION, ACCOMMODATION -> FrontOfficeEvent.ChargeKind.CONSUMPTION;
    };
  }
}
