package io.mateu.ecdemo1.frontoffice.application;

import io.mateu.ecdemo1.frontoffice.domain.cashier.Payment;
import io.mateu.ecdemo1.frontoffice.domain.guest.GuestRepository;
import io.mateu.ecdemo1.frontoffice.domain.guest.PaxKardexes.PaxKardex;
import io.mateu.ecdemo1.frontoffice.infra.scanner.DemoDocuments;
import io.mateu.ecdemo1.integration.model.registration.RegistrationRuleChanged.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

/**
 * The guest's self check-in on the lobby tablet (Civitfun) — <b>simulated</b>, as the desk's document
 * scanner is: nobody touches a tablet; the desk sends the stay and the simulation does what the guest
 * would do there, step by step — scan their document, fill in their data, keep their room or take the
 * upgrade, guarantee or pay the stay, sign the registration. Each step is the desk's own use case, so the
 * MDM, the till and the check-in's operations learn it as if the desk had done it; the desk only hands
 * over the key.
 *
 * <p>The guest's answers are made up from the stay — the same stay, the same answers — like the demo
 * scanner's documents.
 */
@Service
// a service, never part of a page's state (Mateu serialises the pages, and the services some of them hold)
@com.fasterxml.jackson.annotation.JsonIgnoreType
public class TabletCheckIn {

  /** The suite the tablet offers as an upgrade (the desk's, {@code LlegadaPanel.SUITE_UPGRADE}). */
  public static final String SUITE_UPGRADE = "1401";

  /** Who did it, for the audit trail and the kárdex. */
  public static final String GUEST = "huésped (tableta Civitfun)";

  /** The steps, as the desk's progress dialog names them. */
  public static final List<String> STEPS = List.of("Escaneando el documento en la tableta…",
      "El huésped completa sus datos…", "El huésped elige habitación…", "El huésped garantiza la estancia…",
      "El huésped firma el registro…");

  final StayQueries queries;
  final GuestRepository guests;
  final KardexService kardex;
  final RoomChangeService rooms;
  final CheckInService checkIn;
  final Cashier cashier;
  final StayAudit audit;

  public TabletCheckIn(StayQueries queries, GuestRepository guests, KardexService kardex, RoomChangeService rooms,
                       CheckInService checkIn, Cashier cashier, StayAudit audit) {
    this.queries = queries;
    this.guests = guests;
    this.kardex = kardex;
    this.rooms = rooms;
    this.checkIn = checkIn;
    this.cashier = cashier;
    this.audit = audit;
  }

  /** Runs step {@code n} (1…5) of the guest's tablet check-in; what the guest did, for the desk. */
  public String step(String stayId, int n) {
    queries.find(stayId).orElseThrow(() -> new NoSuchElementException("Reserva " + stayId + " no encontrada"));
    return switch (n) {
      case 1 -> scan(stayId);
      case 2 -> data(stayId);
      case 3 -> room(stayId);
      case 4 -> pay(stayId);
      case 5 -> sign(stayId);
      default -> throw new IllegalArgumentException("The tablet has 5 steps, not " + n);
    };
  }

  /** The whole check-in, every step; what the guest did. */
  public List<String> simulate(String stayId) {
    return java.util.stream.IntStream.rangeClosed(1, STEPS.size()).mapToObj(i -> step(stayId, i)).toList();
  }

  String scan(String stayId) {
    var d = kardex.scanned(stayId, 1);
    return "Documento leído: " + d.documentType() + " " + d.documentNumber();
  }

  /** What a guest of that nationality types: an address of their country, their language, their consent. */
  record Answers(String address, String postalCode, String city, String province, String country, String language,
                 boolean marketing) {}

  static final List<Answers> PLACES = List.of(
      new Answers("Calle Mayor 12", "28013", "Madrid", "Madrid", "ES", "es", true),
      new Answers("Unter den Linden 8", "10117", "Berlin", "Berlin", "DE", "de", false),
      new Answers("221B Baker Street", "NW1 6XE", "London", "Greater London", "GB", "en", true),
      new Answers("12 Rue de Rivoli", "75001", "Paris", "Île-de-France", "FR", "fr", false),
      new Answers("Kalverstraat 92", "1012 PH", "Amsterdam", "Noord-Holland", "NL", "nl", true),
      new Answers("Drottninggatan 50", "111 21", "Stockholm", "Stockholm", "SE", "sv", false));

  Answers answersFor(String stayId) {
    var nationality = registrationNationality(stayId);
    return PLACES.stream().filter(p -> p.country().equalsIgnoreCase(nationality)).findFirst()
        .orElse(PLACES.get((int) Math.floorMod(DemoDocuments.hash("tablet:" + stayId), (long) PLACES.size())));
  }

  String registrationNationality(String stayId) {
    return kardex.registrationData == null ? null
        : java.util.Optional.ofNullable(kardex.registrationData.of(stayId, 1).get(Field.NATIONALITY))
            .map(n -> n.length() == 3 ? iso2(n) : n).orElse(null);
  }

  static String iso2(String iso3) {
    for (var c : Locale.getISOCountries()) {
      if (iso3.equalsIgnoreCase(Locale.of("", c).getISO3Country())) {
        return c;
      }
    }
    return iso3;
  }

  String data(String stayId) {
    var a = answersFor(stayId);
    var view = queries.view(stayId);
    var guest = view.guest();
    var email = guest != null && guest.email() != null ? guest.email()
        : (guest == null ? "guest" : guest.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", ".")) + "@example.com";
    kardex.contactUpdated(stayId, 1, email, guest == null ? null : guest.phone());
    var values = new EnumMap<Field, String>(Field.class);
    values.put(Field.ADDRESS, a.address());
    values.put(Field.POSTAL_CODE, a.postalCode());
    values.put(Field.CITY, a.city());
    values.put(Field.COUNTRY_OF_RESIDENCE, a.country());
    kardex.registrationData(stayId, 1, values);
    var before = kardex.kardexOf(stayId, 1).orElse(null);
    var names = guest == null ? new String[] {null, null} : split(guest.name());
    kardex.kardexFilled(stayId, 1, new PaxKardex(stayId, 1,
        before != null && before.firstName() != null ? before.firstName() : names[0],
        before != null && before.lastName() != null ? before.lastName() : names[1],
        before == null ? null : before.riuClass(), before == null ? null : before.documentIssueDate(), a.language(),
        a.province(), before == null ? null : before.fax(), a.marketing(), null, null), GUEST);
    return "Datos completados: " + a.address() + ", " + a.postalCode() + " " + a.city() + " · idioma " + a.language()
        + (a.marketing() ? " · acepta publicidad" : " · sin publicidad");
  }

  /**
   * The guest chooses their room on the tablet: one in three takes the suite, when it is free; the others
   * pick another free room of their type (the reservation changes, as when the desk changes it); with no
   * other free, they keep theirs.
   */
  String room(String stayId) {
    var stay = queries.view(stayId).stay();
    if (!SUITE_UPGRADE.equals(stay.roomNumber()) && Math.floorMod(DemoDocuments.hash("upgrade:" + stayId), 3L) == 0
        && rooms.changeRoom(stayId, SUITE_UPGRADE).isPresent()) {
      return "Ha elegido el upgrade: suite " + SUITE_UPGRADE;
    }
    var free = rooms.freeRoomsOfItsType(stayId);
    if (!free.isEmpty()) {
      var chosen = free.get((int) Math.floorMod(DemoDocuments.hash("room:" + stayId), (long) free.size()));
      if (rooms.changeRoom(stayId, chosen.number()).isPresent()) {
        return "Ha elegido la habitación " + chosen.number() + " (" + chosen.typeLabel() + ")"
            + (stay.roomNumber() == null ? "" : " en lugar de la " + stay.roomNumber());
      }
    }
    return "Se queda con su habitación" + (stay.roomNumber() == null ? " (" + stay.roomType() + ")" : " " + stay.roomNumber());
  }

  /** One in four pays the stay now (an advance, with its receipt); the others pre-authorize their card. */
  String pay(String stayId) {
    var total = queries.view(stayId).stay().total();
    if (Math.floorMod(DemoDocuments.hash("pay:" + stayId), 4L) == 0) {
      var due = cashier.account(stayId).due();
      var p = cashier.take(stayId, Payment.Kind.DEPOSIT, Payment.Method.CARD_PINPAD, due.signum() > 0 ? due : total, null,
          "Tableta", GUEST);
      if (p.captured()) {
        checkIn.paymentTaken(stayId, "card-tablet-paid", total);
        return "Ha pagado la estancia con tarjeta en la tableta: " + Receipts.amount(p.amount(), p.currency())
            + " (recibo nº " + p.receiptNo() + ")";
      }
    }
    checkIn.paymentTaken(stayId, "card-tablet-preauth", total);
    return "Ha preautorizado " + Receipts.amount(total, "EUR") + " en su tarjeta";
  }

  String sign(String stayId) {
    checkIn.registrationSigned(stayId, GUEST);
    return "Registro firmado en la tableta";
  }

  static String[] split(String name) {
    var n = name == null ? "" : name.trim();
    var space = n.indexOf(' ');
    return space < 0 ? new String[] {n.isEmpty() ? null : n, null} : new String[] {n.substring(0, space), n.substring(space + 1)};
  }
}
