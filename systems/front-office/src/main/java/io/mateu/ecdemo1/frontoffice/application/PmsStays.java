package io.mateu.ecdemo1.frontoffice.application;

import io.mateu.ecdemo1.frontoffice.domain.stay.Companion;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.WalkIn;
import io.mateu.ecdemo1.frontoffice.domain.stay.WalkIns;
import io.mateu.ecdemo1.messaging.Inbox;
import io.mateu.ecdemo1.frontoffice.infra.pms.PmsCatalogue;
import io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.CatalogueType;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.RecordReception;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.ReplaceCatalogue;
import io.mateu.ecdemo1.frontoffice.infra.pms.ReceptionReports;
import io.mateu.ecdemo1.frontoffice.infra.pms.StayInvoices;
import io.mateu.ecdemo1.integration.model.frontoffice.FrontOfficeCommand.WriteStay;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The front office consumes the PMS (the chain CRS → PMS → front office): the pms-fo integration
 * tells it each reservation as the PMS holds it, and the PMS's catalogue to read it with. Each command
 * is applied once — its id goes to the inbox in the same transaction — and a stay is written by
 * state, ordered by the PMS's version of the reservation, so nothing is undone by a late or repeated
 * message.
 *
 * <p>What the PMS says replaces what the reservation said; what happened at the hotel — the room, the
 * check-in, the people the desk registered — is the desk's and stays. A reservation the desk already
 * has — a CRS locator, a walk-in the desk opened — is recognised, never opened twice.
 */
@Service
public class PmsStays {

  static final Logger log = LoggerFactory.getLogger(PmsStays.class);
  public static final String ROOM_ONLY = "Solo alojamiento";

  /** What became of a command. */
  public enum Outcome { WRITTEN, CANCELLED, NO_SHOW, KEPT_BY_THE_DESK, STALE, UNKNOWN_STAY, CATALOGUE, DUPLICATE, OTHER_HOTEL,
    RECEPTION, NOT_A_STAY }

  final String pmsHotel;
  final StayRepository stays;
  final WalkIns walkIns;
  final StayWrites writes;
  final PmsCatalogue catalogue;
  final PmsLinks links;
  /** Who the inbox knows these commands by; the ids the front office took before are kept under it. */
  public static final String CONSUMER = "front-office";

  final Inbox inbox;
  final ReceptionReports reception;
  final StayInvoices invoices;
  final io.mateu.ecdemo1.frontoffice.infra.pms.ChargePostings chargePostings;
  final Clock clock = Clock.systemUTC();

  public PmsStays(@Value("${frontoffice.pms-hotel:XMAR}") String pmsHotel, StayRepository stays, WalkIns walkIns,
      StayWrites writes, PmsCatalogue catalogue, PmsLinks links, Inbox inbox, ReceptionReports reception,
      StayInvoices invoices, io.mateu.ecdemo1.frontoffice.infra.pms.ChargePostings chargePostings) {
    this.chargePostings = chargePostings;
    this.pmsHotel = pmsHotel;
    this.stays = stays;
    this.walkIns = walkIns;
    this.writes = writes;
    this.catalogue = catalogue;
    this.links = links;
    this.inbox = inbox;
    this.reception = reception;
    this.invoices = invoices;
  }

  public String pmsHotel() {
    return pmsHotel;
  }

  /** A command of the pms-fo integration, once. One for another property is not this front office's. */
  @Transactional
  public Outcome take(FrontOfficeCommand command) {
    var hotel = switch (command) {
      case WriteStay w -> w.pmsHotelCode();
      case ReplaceCatalogue c -> c.pmsHotelCode();
      case RecordReception r -> r.pmsHotelCode();
      case FrontOfficeCommand.RecordCharge c -> c.pmsHotelCode();
    };
    if (!pmsHotel.equals(hotel)) {
      log.debug("Command {} is for PMS property {}, not {}: not this front office's", command.commandId(), hotel, pmsHotel);
      return Outcome.OTHER_HOTEL;
    }
    if (command.commandId() != null && !command.commandId().isBlank()
        && !inbox.firstTime(CONSUMER, command.commandId())) {
      log.debug("Command {} already taken", command.commandId());
      return Outcome.DUPLICATE;
    }
    return switch (command) {
      case WriteStay w -> write(w);
      case ReplaceCatalogue c -> {
        catalogue.replace(c.pmsHotelCode(), c.commandId(), c.entries(), clock.instant());
        log.info("PMS catalogue of {}: {} entries", c.pmsHotelCode(), c.entries() == null ? 0 : c.entries().size());
        yield Outcome.CATALOGUE;
      }
      case RecordReception r -> record(r);
      case FrontOfficeCommand.RecordCharge c -> charge(c);
    };
  }

  /**
   * How the PMS took a charge of the desk, or its void: posted on its folio (its transaction number),
   * or refused — the line says why, and the process in the PMS waits on a cause someone resolves.
   */
  Outcome charge(FrontOfficeCommand.RecordCharge c) {
    var stayId = Optional.ofNullable(c.stayId()).filter(id -> stays.findById(id).isPresent())
        .or(() -> links.byPmsReservation(c.pmsReservationId()).map(PmsLinks.Link::stayId));
    if (stayId.isEmpty()) {
      log.info("PMS reservation {}: a charge for a stay this front office does not have", c.pmsReservationId());
      return Outcome.UNKNOWN_STAY;
    }
    // the till's payments come back on the line PAY:<payment>
    var payment = c.lineId() != null && c.lineId().startsWith("PAY:");
    var what = payment ? (c.reversal() ? "devolución" : "cobro") : c.reversal() ? "anulación" : "cargo";
    var state = c.refused()
        ? "Opera: rechazado (" + what + ") — " + (c.detail() == null ? "sin motivo" : c.detail())
        : c.reversal() ? (payment ? "Opera: devuelto" : "Opera: anulado") + (c.pmsPostingId() == null ? "" : " · " + c.pmsPostingId())
        : "Opera: en el folio" + (c.pmsPostingId() == null ? "" : " · " + c.pmsPostingId());
    chargePostings.posted(stayId.get(), c.lineId(), c.reversal(), c.refused() ? null : c.pmsPostingId(), state,
        clock.instant());
    log.info("{}: line {} — {}", stayId.get(), c.lineId(), state);
    return Outcome.RECEPTION;
  }

  /**
   * How the PMS took what the desk did: refused — the stay says why, and the process in the PMS waits
   * on a cause someone resolves —, the room the PMS put the guests in, the check-out's invoice.
   */
  Outcome record(RecordReception r) {
    var stayId = links.byPmsReservation(r.pmsReservationId()).map(PmsLinks.Link::stayId)
        .or(() -> Optional.ofNullable(r.stayId()).filter(id -> stays.findById(id).isPresent()));
    if (stayId.isEmpty()) {
      log.info("PMS reservation {}: {} for a stay this front office does not have", r.pmsReservationId(), r.operation());
      return Outcome.UNKNOWN_STAY;
    }
    var id = stayId.get();
    var what = switch (r.operation()) {
      case CHECK_IN -> "check-in";
      case CHECK_OUT -> "check-out";
      case NO_SHOW -> "no show";
    };
    if (r.refused()) {
      links.state(id, "Opera: rechazado (" + what + ") — " + (r.detail() == null ? "sin motivo" : r.detail()));
      log.warn("{}: the PMS refused its {}: {}", id, what, r.detail());
      return Outcome.RECEPTION;
    }
    switch (r.operation()) {
      case CHECK_IN -> {
        var stay = stays.findById(id).orElseThrow();
        // The PMS is the master of the stay: the room it has the guests in is the stay's.
        if (r.roomNumber() != null && !r.roomNumber().isBlank() && !r.roomNumber().equals(stay.roomNumber())
            && stay.status() != io.mateu.ecdemo1.frontoffice.domain.stay.StayStatus.DEPARTED) {
          stays.save(stay.assignRoom(r.roomNumber(), stay.roomType()));
        }
        links.state(id, "Opera: en casa" + (r.roomNumber() == null ? "" : " · hab. " + r.roomNumber()));
      }
      case CHECK_OUT -> {
        if (r.invoice() != null) {
          invoices.save(id, r.invoice(), clock.instant());
        }
        links.state(id, "Opera: salida registrada" + (r.invoice() == null || r.invoice().number() == null ? ""
            : " · factura " + r.invoice().number()));
      }
      case NO_SHOW -> links.state(id, "Opera: no show anotado; el CRS aplica su cargo");
    }
    log.info("{}: the PMS recorded its {}{}", id, what, r.invoice() == null ? "" : " — invoice " + r.invoice().number()
        + (r.invoice().pdf() == null ? " (figures only)" : " (with its document)"));
    return Outcome.RECEPTION;
  }

  Outcome write(WriteStay w) {
    var found = stayOf(w);
    var current = found.flatMap(links::ofStay).map(PmsLinks.Link::pmsVersion).orElse(null);
    if (current != null && w.pmsVersion() != null && current.compareTo(w.pmsVersion()) > 0) {
      log.info("{} v{}: the front office already holds v{} of PMS reservation {}, kept", found.get(), w.pmsVersion(),
          current, w.pmsReservationId());
      return Outcome.STALE;
    }
    var status = w.status() == null ? FrontOfficeCommand.PmsStatus.RESERVED : w.status();
    if (status == FrontOfficeCommand.PmsStatus.CANCELLED || status == FrontOfficeCommand.PmsStatus.NO_SHOW) {
      if (found.isEmpty()) {
        log.info("PMS reservation {} is {} and never was a stay here: nothing to do", w.pmsReservationId(), status);
        return Outcome.UNKNOWN_STAY;
      }
      var stay = stays.findById(found.get()).orElseThrow();
      try {
        stays.save(status == FrontOfficeCommand.PmsStatus.NO_SHOW ? stay.noShow(w.total()) : stay.cancel());
      } catch (IllegalStateException e) {
        log.warn("{}: the PMS has it {}, but the desk keeps its stay — {}", stay.id(), status, e.getMessage());
        links.link(stay.id(), w.pmsReservationId(), w.pmsVersion(), ratePlan(w));
        return Outcome.KEPT_BY_THE_DESK;
      }
      links.link(stay.id(), w.pmsReservationId(), w.pmsVersion(), ratePlan(w));
      log.info("{} {} from the PMS ({})", stay.id(), status == FrontOfficeCommand.PmsStatus.NO_SHOW
          ? "a no-show, costing " + w.total() : "cancelled", w.pmsReservationId());
      return status == FrontOfficeCommand.PmsStatus.NO_SHOW ? Outcome.NO_SHOW : Outcome.CANCELLED;
    }
    var stayId = found.orElseGet(() -> newStayId(w));
    if (status == FrontOfficeCommand.PmsStatus.CHECKED_OUT && found.isPresent()) {
      // Checked out in the PMS: what the stay was is the desk's now — an early departure moves the PMS's
      // departure to its arrival day, a stay no longer shaped as one. Only where it stands in the PMS.
      links.link(stayId, w.pmsReservationId(), w.pmsVersion(), ratePlan(w));
      links.state(stayId, "Opera: salida registrada" + invoices.of(stayId)
          .map(i -> i.number() == null ? "" : " · factura " + i.number()).orElse(""));
      log.info("PMS reservation {} v{} checked out: stay {} says so", w.pmsReservationId(), w.pmsVersion(), stayId);
      return Outcome.WRITTEN;
    }
    if (w.checkIn() == null || w.checkOut() == null || !w.checkOut().isAfter(w.checkIn())) {
      // Not a stay the front office can hold (a day use: no night between arrival and departure).
      // Retrying cannot fix it, and it would hold every command behind it on the partition: skipped.
      log.warn("PMS reservation {} v{} ({}..{}) is no stay this front office can hold, skipped", w.pmsReservationId(),
          w.pmsVersion(), w.checkIn(), w.checkOut());
      return Outcome.NOT_A_STAY;
    }
    var holder = w.holder();
    var written = writes.write(stayId, new StayWrites.Booking(guestId(w),
            new StayWrites.Holder(holder == null ? "" : holder.name(), holder == null ? null : holder.document(),
                holder == null ? null : holder.email(), holder == null ? null : holder.phone()),
            companions(w), roomType(w), board(w), w.checkIn(), w.checkOut(), Math.max(1, w.pax()), w.agency(), w.total()),
        true);
    links.link(stayId, w.pmsReservationId(), w.pmsVersion(), ratePlan(w));
    links.agreed(stayId, w.agreedTotal());
    links.companyFromPms(stayId, w.companyCode(), w.companyName());
    walkInOf(w).ifPresent(walkIn -> walkIns.save(walkIn.cameBack(w.crsLocator() != null ? w.crsLocator() : walkIn.locator(),
        w.pmsReservationId(), clock.instant())));
    // The reception's, as the PMS holds it now: the stay says so. What the desk did before the stay was
    // linked (a walk-in's check-in) goes up now.
    switch (status) {
      case IN_HOUSE -> {
        if (!links.stateOf(stayId).orElse("").startsWith("Opera: en casa")) {
          links.state(stayId, "Opera: en casa");
        }
      }
      case CHECKED_OUT -> links.state(stayId, "Opera: salida registrada" + invoices.of(stayId)
          .map(i -> i.number() == null ? "" : " · factura " + i.number()).orElse(""));
      default -> stays.findById(stayId).ifPresent(reception::pendingLink);
    }
    log.info("PMS reservation {} v{} written onto stay {} ({})", w.pmsReservationId(), w.pmsVersion(), stayId,
        written.created() ? "new" : "changed");
    return Outcome.WRITTEN;
  }

  /**
   * The stay a PMS reservation already is: the one linked to it; the CRS's, by its locator; or the
   * walk-in the desk opened, by the locator the CRS gave it or by the stay id it carries as a reference.
   */
  Optional<String> stayOf(WriteStay w) {
    var linked = links.byPmsReservation(w.pmsReservationId()).map(PmsLinks.Link::stayId);
    if (linked.isPresent()) {
      return linked;
    }
    if (w.crsLocator() != null && stays.findById(w.crsLocator()).isPresent()) {
      return Optional.of(w.crsLocator());
    }
    return walkInOf(w).map(WalkIn::stayId).filter(id -> stays.findById(id).isPresent());
  }

  Optional<WalkIn> walkInOf(WriteStay w) {
    var byLocator = w.crsLocator() == null ? Optional.<WalkIn>empty() : walkIns.byLocator(w.crsLocator());
    if (byLocator.isPresent()) {
      return byLocator;
    }
    for (var ref : w.externalReferences() == null ? List.<String>of() : w.externalReferences()) {
      var walkIn = ref == null ? Optional.<WalkIn>empty() : walkIns.of(ref);
      if (walkIn.isPresent()) {
        return walkIn;
      }
    }
    return Optional.empty();
  }

  /** A stay the front office did not have: the CRS's locator when it came from the CRS, else the PMS's confirmation. */
  static String newStayId(WriteStay w) {
    if (w.crsLocator() != null && !w.crsLocator().isBlank()) {
      return w.crsLocator();
    }
    return "OP-" + (w.confirmationNumber() != null && !w.confirmationNumber().isBlank() ? w.confirmationNumber()
        : w.pmsReservationId());
  }

  /** The chain's customer when the MDM knows the guest; else the PMS's profile, or the reservation itself. */
  static String guestId(WriteStay w) {
    var h = w.holder();
    if (h != null && h.customerId() != null && !h.customerId().isBlank()) {
      return h.customerId();
    }
    if (h != null && h.pmsProfileId() != null && !h.pmsProfileId().isBlank()) {
      return "opera-" + h.pmsProfileId();
    }
    return "pms-" + w.pmsReservationId();
  }

  String roomType(WriteStay w) {
    return catalogue.describe(w.pmsHotelCode(), CatalogueType.ROOM_TYPE, w.roomTypeCode()).orElse(w.roomTypeCode());
  }

  String board(WriteStay w) {
    if (w.boardCode() == null || w.boardCode().isBlank()) {
      return ROOM_ONLY;
    }
    return catalogue.describe(w.pmsHotelCode(), CatalogueType.PACKAGE, w.boardCode()).orElse(w.boardCode());
  }

  String ratePlan(WriteStay w) {
    return catalogue.describe(w.pmsHotelCode(), CatalogueType.RATE_PLAN, w.ratePlanCode()).orElse(w.ratePlanCode());
  }

  /** The room's other people, in pax order: the holder is pax 1 and is not among them. */
  static List<Companion> companions(WriteStay w) {
    var list = w.companions() == null ? List.<FrontOfficeCommand.Person>of() : w.companions();
    var result = new ArrayList<Companion>();
    for (int i = 0; i < list.size(); i++) {
      var p = list.get(i);
      result.add(new Companion(p.customerId() == null ? "pax-" + (i + 2) : p.customerId(), p.name(), p.document(),
          false, p.email(), p.phone(), "Pax " + (i + 2) + " · de la reserva"));
    }
    return result;
  }
}
