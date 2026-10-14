package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.core.infra.declarative.orchestrators.wizard.Wizard;
import io.mateu.ecdemo1.frontoffice.application.CheckInService;
import io.mateu.ecdemo1.frontoffice.application.StayQueries;
import io.mateu.ecdemo1.frontoffice.domain.room.Room;
import io.mateu.ecdemo1.frontoffice.domain.room.RoomRepository;
import io.mateu.ecdemo1.frontoffice.ui.common.DeskSimulations;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.fluent.Action;
import io.mateu.uidl.interfaces.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * The check-in flow of one arriving stay (route {@code /checkin/:id}): one step per operation still
 * pending when it opens — Avisos, Identidad, Habitación, Firma, Extras, Cobro, Llave, Wifi — and the
 * last, Confirmar, with the summary and «Confirmar check-in»; plus the read-only result screen. Every step opens with the guest's
 * {@code EntityHeader} so the context never leaves the screen; the room grid, the upgrade offer and
 * the add-on picker dispatch their actions back into this wizard.
 *
 * <p>State is handled by the framework: every step's fields are serialized into the component
 * state and rehydrated on each request, so values entered or set in ANY step survive navigation
 * and actions for the wizard's lifetime. The wizard itself only seeds the steps once per stay
 * ({@link #populate()}), keeps the Confirmar summary derived from the other steps
 * ({@link #syncConfirmar()}), and reacts to the actions its step components dispatch. Confirming
 * is the {@link CheckInService#checkIn} use case, one transaction: assign the room, check the stay in,
 * occupy the room and open the folio with the accommodation and add-on charges.
 *
 * <p>A prototype bean: Mateu takes it from Spring and hydrates it, so the services are injected. Its
 * steps are not — the wizard creates them and Mateu re-creates them from the state — and read through
 * the static {@code FrontOffice} gateway.
 */
@Service
@Scope("prototype")
@Title("Check-In")
@Style(StyleConstants.CONTAINER)
@WizardProgress(WizardProgressStyle.STEPS)
// the screens are written in Spanish; without this the wizard's own buttons read Back / Next
@WizardLabels(back = "Atrás", next = "Siguiente")
// a scan changes the registroPax colors (per-pax green + band theme) — re-render the step
@SubscribeTo(event = "documento-escaneado", action = "refrescarIdentidad")
// the desk's devices (tablet, card terminal, key encoder, wifi portal) work in a progress dialog
// (DeskSimulations) that dispatches one of these events when it closes: the green state arrives via
// a FRESH action, which records the operation and repaints the step
@SubscribeTo(event = "firma-capturada", action = "firmaCapturada")
@SubscribeTo(event = "preautorizacion-completada", action = "preautorizado")
@SubscribeTo(event = "llave-grabada", action = "llaveGrabada")
@SubscribeTo(event = "wifi-creada", action = "wifiCreada")
public class CheckInWizard extends Wizard {

  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CheckInWizard.class);

  String stayId;
  boolean populated;
  int selectedPax = 1;

  /**
   * The conditional steps this check-in run shows, comma separated — frozen when the run opens
   * ({@link #populate()}): a step that was pending then stays on the train once done (✓) instead of
   * vanishing because it no longer is pending.
   */
  String pasos;

  /** The steps shown only when something they do is pending; the rest always apply. */
  static final List<String> PASOS_CONDICIONALES =
      List.of("avisos", "identidad", "habitacion", "firma", "extras", "cobro", "llave", "wifi");

  @Label("Avisos")
  AvisosStep avisos = new AvisosStep();

  @Label("Identidad")
  IdentidadStep identidad;

  @Label("Habitación")
  HabitacionStep habitacion = new HabitacionStep();

  @Label("Firma")
  FirmaStep firma = new FirmaStep();

  @Label("Extras")
  ExtrasStep extras = new ExtrasStep();

  @Label("Cobro")
  CobroStep cobro = new CobroStep();

  @Label("Llave")
  LlaveStep llave = new LlaveStep();

  @Label("Wifi")
  WifiStep wifi = new WifiStep();

  @Label("Confirmar")
  ConfirmarStep confirmar = new ConfirmarStep();

  @Label("Check-in completado")
  ResultStep result;

  final StayQueries queries;
  final CheckInService checkIn;
  final RoomRepository rooms;
  final io.mateu.ecdemo1.frontoffice.application.GuestNotices notices;
  final io.mateu.ecdemo1.frontoffice.application.IncompleteCheckIns incomplete;

  final io.mateu.ecdemo1.frontoffice.application.Recognition recognition;

  public CheckInWizard(StayQueries queries, CheckInService checkIn, RoomRepository rooms,
                       io.mateu.ecdemo1.frontoffice.application.GuestNotices notices,
                       io.mateu.ecdemo1.frontoffice.application.IncompleteCheckIns incomplete,
                       io.mateu.ecdemo1.frontoffice.application.Recognition recognition) {
    this.recognition = recognition;
    this.queries = queries;
    this.incomplete = incomplete;
    this.checkIn = checkIn;
    this.rooms = rooms;
    this.notices = notices;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────────

  @Override
  public void onHydrated(HttpRequest httpRequest) {
    super.onHydrated(httpRequest);
    var id = GuestHeaders.idFromRoute(httpRequest, "checkin");
    if (id != null && !id.equals(stayId)) {
      stayId = id;
      populated = false;
      selectedPax = 1;
    }
    if (!populated) {
      populated = true;
      populate();
    }
    identidad = new IdentidadStep();
    identidad.setSelectedPax(selectedPax);
    identidad.load(httpRequest);
    syncConfirmar();
  }

  /** Seeds the steps from the stay's reservation data — once per stay. */
  void populate() {
    var view = queries.view(stayId);
    if (avisos == null) {
      avisos = new AvisosStep();
    }
    avisos.setStayId(stayId);
    avisos.setFingerprint(notices.checkInFingerprint(view.stay()));
    avisos.setLeido(false);
    // Mateu instantiates the step it opens on from the state; with Avisos first, Identidad may not be yet
    if (identidad == null) {
      identidad = new IdentidadStep();
    }
    identidad.setStayId(stayId);
    habitacion.setStayId(stayId);
    habitacion.setHabitacionSeleccionada(view.stay().roomNumber());
    extras.setStayId(stayId);
    extras.setExtrasSeleccionados("");
    extras.setExtrasTotal(0);
    firma.setStayId(stayId);
    cobro.setStayId(stayId);
    llave.setStayId(stayId);
    wifi.setStayId(stayId);
    confirmar.setStayId(stayId);
    // the run's steps: what is pending NOW, kept for the whole run
    pasos = null;
    pasos = String.join(",", PASOS_CONDICIONALES.stream().filter(this::stepApplies).toList());
    // operaciones ya completadas desde la Reserva 360 (o un wizard anterior): su paso no sale, y si
    // saliera, las muestra en verde
    var ops = queries.ops(stayId);
    if (ops.llave()) {
      llave.setLlaveEstado("grabada");
    }
    if (ops.firma()) {
      firma.setFirmaEstado("firmada");
    }
    if (ops.cobro()) {
      cobro.setPreauthEstado("preautorizado");
    }
    wifi.setCreada(ops.wifi());
  }

  /** The room the key is written for: the one picked in the wizard, else the stay's. */
  String roomNumber() {
    var picked = habitacion.getHabitacionSeleccionada();
    return picked != null && !picked.isBlank() ? picked : queries.view(stayId).stay().roomNumber();
  }

  /** The Confirmar step shows data derived from the other steps — recompute it on each request. */
  void syncConfirmar() {
    var view = queries.view(stayId);
    var stay = view.stay();
    confirmar.setHuespedPrincipal(view.guest().name());
    var room =
        habitacion.getHabitacionSeleccionada() != null
            ? habitacion.getHabitacionSeleccionada()
            : stay.roomNumber();
    confirmar.setHabitacionAsignada(room == null || room.isBlank()
        ? "Sin asignar — " + stay.roomType()
        : room + " — " + roomTypeOf(room, stay.roomType()));
    confirmar.setEstancia(GuestHeaders.stayDates(stay));
    confirmar.setRegimen(stay.board());
    confirmar.setTotalEstancia(stay.total().doubleValue() + extras.getExtrasTotal());
    confirmar.setTotal(GuestHeaders.euros(confirmar.getTotalEstancia()));
    cobro.setTotalEstancia(confirmar.getTotalEstancia());
  }

  /** The selected room's type from the room inventory, falling back to the reservation's. */
  String roomTypeOf(String roomNumber, String fallback) {
    return rooms.findByNumber(roomNumber).map(Room::typeLabel).orElse(fallback);
  }

  // ── Actions dispatched by the step components ──────────────────────────────

  @Override
  public Object handleAction(String actionId, HttpRequest httpRequest) {
    switch (actionId) {
      case "volverReserva" -> {
        return java.net.URI.create("/reservas/" + stayId);
      }
      case "crearWifi" -> {
        return DeskSimulations.wifi(WifiStep.clave(stayId), "wifi-creada");
      }
      case "wifiCreada" -> {
        checkIn.wifiCreated(stayId);
        wifi.setCreada(true);
        return List.of(this, new Message("Tarjeta wifi creada — red HOTEL_GUEST · clave " + WifiStep.clave(stayId)));
      }
      case "pickRoom" -> {
        var item = param(httpRequest, "_item");
        if (item != null) {
          habitacion.setHabitacionSeleccionada(item);
          syncConfirmar();
        }
        return this;
      }
      case "upgrade" -> {
        // toggle: the offer card's CTA turns green ("✓ Upgrade añadido") while active
        habitacion.setUpgradeAnadido(!habitacion.isUpgradeAnadido());
        syncConfirmar();
        return List.of(
            this,
            new Message(
                habitacion.isUpgradeAnadido()
                    ? "Upgrade aplicado — Master Oceanfront Suite (+ € 65 / noche)"
                    : "Upgrade retirado"));
      }
      case "extrasChanged" -> {
        var params = httpRequest.runActionRq().parameters();
        var item = String.valueOf(params.get("_item"));
        var added = Boolean.parseBoolean(String.valueOf(params.get("_added")));
        var ids = new LinkedHashSet<>(extras.addedIds());
        if (added) {
          ids.add(item);
        } else {
          ids.remove(item);
        }
        extras.setExtrasSeleccionados(String.join(",", ids));
        if (params.get("_total") instanceof Number total) {
          extras.setExtrasTotal(total.doubleValue());
        }
        syncConfirmar();
        return this;
      }
      case "selectPax" -> {
        var raw = param(httpRequest, "paxIndex");
        if (raw != null) {
          selectedPax = (int) Double.parseDouble(raw);
          identidad.setSelectedPax(selectedPax);
          identidad.load(httpRequest);
        }
        // re-render the step (selected button) AND re-point the Documento island to the pax
        return List.of(
            this, UICommand.dispatchEvent("pax-seleccionado", Map.of("paxIndex", selectedPax)));
      }
      case "refrescarIdentidad" -> {
        return siguientePax(httpRequest);
      }
      case "encodeKey" -> {
        return DeskSimulations.llave(roomNumber(), "llave-grabada");
      }
      case "llaveGrabada" -> {
        llave.setLlaveEstado("grabada");
        checkIn.keyEncoded(stayId);
        return List.of(this, new Message("Llave / pulsera grabada"));
      }
      case "requestPreauth" -> {
        return DeskSimulations.preautorizacion(GuestHeaders.euros(confirmar.getTotalEstancia()),
            "preautorizacion-completada");
      }
      case "preautorizado" -> {
        cobro.setPreauthEstado("preautorizado");
        checkIn.paymentTaken(stayId, "card", confirmar.getTotalEstancia() == null ? null : java.math.BigDecimal.valueOf(confirmar.getTotalEstancia()));
        return List.of(
            this,
            new Message(
                "Preautorizado — " + GuestHeaders.euros(confirmar.getTotalEstancia())));
      }
      case "sendToTablet" -> {
        return DeskSimulations.firma("firma-capturada");
      }
      case "forzarCheckin" -> {
        return forzarCheckin();
      }
      case "firmaCapturada" -> {
        firma.setFirmaEstado("firmada");
        checkIn.registrationSigned(stayId, io.mateu.ecdemo1.frontoffice.infra.security.DeskUser.name());
        return List.of(this, new Message("Firma capturada"));
      }
      default -> {
        var result = super.handleAction(actionId, httpRequest);
        syncConfirmar();
        return result;
      }
    }
  }

  /**
   * A document was scanned (or registered by hand) for the selected pax: the desk goes on to the next
   * pax still lacking identity — the band selects it and the Documento island re-points to it — and,
   * once none is left, to the next step. Any other refresh (a contact edit) just re-renders.
   */
  Object siguientePax(HttpRequest httpRequest) {
    if (!Boolean.parseBoolean(param(httpRequest, "avanzar"))
        || !"identidad".equals(currentStepField().getName())) {
      return this;
    }
    // the scan recognised the pax — or possibly: the desk stays on them to see it (and to confirm)
    if (recognition.hasNews(stayId, selectedPax)) {
      identidad.load(httpRequest);
      return this;
    }
    var next = queries.nextPendingPax(stayId, selectedPax);
    if (next > 0) {
      selectedPax = next;
      identidad.setSelectedPax(selectedPax);
      identidad.load(httpRequest);
      return List.of(
          this, UICommand.dispatchEvent("pax-seleccionado", Map.of("paxIndex", selectedPax)));
    }
    var result = super.handleAction("next", httpRequest);
    syncConfirmar();
    return result;
  }

  static String param(HttpRequest httpRequest, String name) {
    var rq = httpRequest.runActionRq();
    if (rq == null || rq.parameters() == null || rq.parameters().get(name) == null) {
      return null;
    }
    return String.valueOf(rq.parameters().get(name));
  }

  // advertise the component-dispatched action ids so the frontend sends them to this component
  @Override
  public List<Action> actions(HttpRequest httpRequest) {
    var actions = new ArrayList<>(super.actions(httpRequest));
    for (var id :
        List.of(
            "pickRoom",
            "upgrade",
            "extrasChanged",
            "selectPax",
            "refrescarIdentidad",
            "firmaCapturada",
            "preautorizado",
            "llaveGrabada",
            "wifiCreada",
            "forzarCheckin",
            "volverReserva")) {
      actions.add(Action.builder().id(id).build());
    }
    // a device at work: a progress dialog over SSE (DeskSimulations)
    for (var id : List.of("sendToTablet", "requestPreauth", "encodeKey", "crearWifi")) {
      actions.add(Action.builder().id(id).sse(true).build());
    }
    return actions;
  }

  // ── Completion ──────────────────────────────────────────────────────────────

  /** Solo lo que FALTA — un paso por operación de check-in pendiente: avisos si los hay; identidad
   *  si hay documentación pendiente; habitación si no tiene ninguna asignada; firma, extras, cobro,
   *  llave y wifi si su operación no está hecha. Confirmar (el resumen), siempre.
   *  Una estancia ya en casa (un check-in forzado que se completa, «Completar») solo abre lo que
   *  el check-in le debe: la documentación y la firma. */
  @Override
  protected boolean stepApplies(String stepFieldName) {
    if (stayId == null || stayId.isBlank()) {
      return true;
    }
    if (pasos != null) {
      return pasoDeLaRun(pasos, stepFieldName);
    }
    if (completando()) {
      return switch (stepFieldName) {
        case "identidad" -> queries.pendingPax(queries.view(stayId).stay()) > 0;
        case "firma" -> !queries.ops(stayId).firma();
        case "avisos", "habitacion", "extras", "cobro", "llave", "wifi" -> false;
        default -> true;
      };
    }
    return switch (stepFieldName) {
      // los avisos de recepción de los huéspedes, si tienen alguno para el check-in (Salesforce, vía el MDM)
      case "avisos" -> !notices.forStay(queries.view(stayId).stay(),
          io.mateu.ecdemo1.frontoffice.domain.notice.Notice.Moment.CHECK_IN).isEmpty();
      case "identidad" -> queries.pendingPax(queries.view(stayId).stay()) > 0;
      case "habitacion" -> !queries.view(stayId).stay().hasRoom();
      case "extras" -> !queries.ops(stayId).extras();
      case "firma" -> !queries.ops(stayId).firma();
      case "cobro" -> !queries.ops(stayId).cobro();
      case "llave" -> !queries.ops(stayId).llave();
      case "wifi" -> !queries.ops(stayId).wifi();
      default -> true;
    };
  }

  /** Whether a step belongs to the run whose frozen conditional steps are {@code pasos}. */
  static boolean pasoDeLaRun(String pasos, String stepFieldName) {
    return !PASOS_CONDICIONALES.contains(stepFieldName)
        || java.util.Arrays.asList(pasos.split(",")).contains(stepFieldName);
  }

  /** The stay is already in (its check-in was forced): the wizard completes what it owes. */
  boolean completando() {
    return queries.find(stayId).map(s -> s.status() == io.mateu.ecdemo1.frontoffice.domain.stay.StayStatus.IN_HOUSE)
        .orElse(false);
  }

  /**
   * «Forzar check-in»: in with steps missing, and a reason — the stay stays «Check-in incompleto»
   * (audited) and goes up to the PMS like any check-in.
   */
  Object forzarCheckin() {
    syncConfirmar();
    var motivo = confirmar.getMotivoForzado();
    if (motivo == null || motivo.isBlank()) {
      return List.of(this, new Message("⛔ Para forzar el check-in hay que escribir el motivo"));
    }
    var by = io.mateu.ecdemo1.frontoffice.infra.security.DeskUser.name();
    var stay = queries.stay(stayId);
    var falta = incomplete.status(stay).missingText();
    try {
      if (avisos.isLeido() && !notices.checkInAcknowledged(stay)) {
        notices.acknowledgeCheckIn(stayId, by, avisos.getFingerprint());
      }
      checkIn.forceCheckIn(stayId, habitacion.getHabitacionSeleccionada(), extras.addedIds(), motivo, by);
    } catch (io.mateu.ecdemo1.frontoffice.application.GuestNotices.NotAcknowledged e) {
      avisos.setLeido(false);
      avisos.setFingerprint(notices.checkInFingerprint(stay));
      return List.of(this, new Message("⛔ " + e.getMessage()));
    }
    confirmar.setMotivoForzado(null);
    log.info("{}: check-in forced from the wizard by {}, pending {}", stayId, by, falta);
    // de vuelta a la Reserva 360, que ya dice «Check-in incompleto» y lo que falta (una URI, como
    // «Confirmar check-in»: un navigateTo junto a un mensaje no navega desde el asistente)
    return java.net.URI.create("/reservas/" + stayId);
  }

  @WizardCompletionAction
  @Label("Confirmar check-in")
  Object confirmarCheckin() {
    syncConfirmar();
    var by = io.mateu.ecdemo1.frontoffice.infra.security.DeskUser.name();
    var stay = queries.stay(stayId);
    if (completando()) {
      // «Completar»: el check-in forzado queda completo si ya no falta nada
      try {
        incomplete.complete(stayId, by);
      } catch (io.mateu.ecdemo1.frontoffice.application.IncompleteCheckIns.CheckInIncomplete e) {
        return List.of(this, new Message("⛔ " + e.getMessage()));
      }
      return java.net.URI.create("/reservas/" + stayId);
    }
    try {
      // «He leído el aviso»: lo leído es lo que se le mostró; si ha cambiado, se vuelve a pedir
      if (avisos.isLeido() && !notices.checkInAcknowledged(stay)) {
        notices.acknowledgeCheckIn(stayId, by, avisos.getFingerprint());
      }
      checkIn.checkIn(stayId, habitacion.getHabitacionSeleccionada(), extras.addedIds(), by);
    } catch (io.mateu.ecdemo1.frontoffice.application.GuestNotices.NotAcknowledged e) {
      avisos.setLeido(false);
      avisos.setFingerprint(notices.checkInFingerprint(stay));
      return List.of(this, new Message("⛔ " + e.getMessage()));
    } catch (io.mateu.ecdemo1.frontoffice.application.IncompleteCheckIns.CheckInIncomplete e) {
      return List.of(this, new Message("⛔ " + e.getMessage() + " («Forzar check-in», más arriba)"));
    }
    result = new ResultStep();
    result.setStayId(stayId);
    result.setHabitacionFinal(confirmar.getHabitacionAsignada());
    result.setMensaje(
        "✅ Check-in completado — "
            + confirmar.getHuespedPrincipal()
            + " · Habitación "
            + confirmar.getHabitacionAsignada());
    // de vuelta a la Reserva 360 (que ya mostrará el estado in house)
    return java.net.URI.create("/reservas/" + stayId);
  }
}
