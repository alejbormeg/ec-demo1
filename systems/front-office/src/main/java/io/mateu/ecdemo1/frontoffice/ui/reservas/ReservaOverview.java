package io.mateu.ecdemo1.frontoffice.ui.reservas;

import io.mateu.ecdemo1.frontoffice.ui.checkin.DocumentoView;

import io.mateu.ecdemo1.frontoffice.application.CheckInService;
import io.mateu.ecdemo1.frontoffice.application.CheckOutService;
import io.mateu.ecdemo1.frontoffice.application.FolioService;
import io.mateu.ecdemo1.frontoffice.application.GuestNotices;
import io.mateu.ecdemo1.frontoffice.infra.security.DeskUser;
import io.mateu.ecdemo1.frontoffice.application.IncidentService;
import io.mateu.ecdemo1.frontoffice.application.KardexService;
import io.mateu.ecdemo1.frontoffice.application.NoShowService;
import io.mateu.ecdemo1.frontoffice.application.RoomChangeService;
import io.mateu.ecdemo1.frontoffice.application.StayQueries;
import io.mateu.ecdemo1.frontoffice.application.StayView;
import io.mateu.ecdemo1.frontoffice.domain.catalog.AddOnCatalogRepository;
import io.mateu.ecdemo1.frontoffice.domain.catalog.ChargeCatalogRepository;
import io.mateu.ecdemo1.frontoffice.domain.room.RoomRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.IncidentType;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayStatus;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.ecdemo1.frontoffice.ui.common.OtherSystems;
import io.mateu.uidl.annotations.AutoSave;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.SubscribeTo;
import io.mateu.uidl.annotations.SubscribesTo;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.BannerTheme;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.ButtonStyle;
import io.mateu.uidl.data.FoldoutLayout;
import io.mateu.uidl.data.FoldoutPanel;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.LongTask;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.PageBanner;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.TextContainer;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Action;
import io.mateu.uidl.fluent.ActionSupplier;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.fluent.UserTrigger;
import io.mateu.uidl.interfaces.ActionHandler;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PageWidthSupplier;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import io.mateu.uidl.interfaces.ToolbarSupplier;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Reserva 360 — la pantalla ÚNICA de una reserva, sea cual sea su estado: el header del huésped,
 * el resumen de la estancia, los huéspedes con su estado documental y, según el estado, el balance
 * e incidencias (in house) o el folio cerrado (salida). Las ACCIONES del toolbar cambian por
 * estado: "Iniciar check-in" (por llegar) abre el wizard que pide SOLO la información que falta;
 * "Check-out" (in house) abre el folio.
 *
 * <p>This class is the page — its state, its layout, its toolbar and its actions; the panels are
 * drawn by {@link HuespedesPanel}, {@link LlegadaPanel}, {@link EstanciaPanel} and {@link
 * ReservaDrawers}. Every action that changes something is one use case of the application layer, in
 * one transaction. A prototype bean: Mateu takes it from Spring, so the services are injected.
 */
@Getter
@Setter
@Title("Reserva")
// anatomía RDS del foldout: página a sangre (sin gutters ni tope de ancho)
@FormLayout(columns = 1)
@SubscribesTo({
  @SubscribeTo(event = "documento-escaneado", action = "refrescarReserva"),
  @SubscribeTo(event = "firma-capturada-360", action = "opFirmaDone"),
  // el drawer del cardex cierra emitiendo este evento → el rail de huéspedes se refresca
  @SubscribeTo(event = "cardex-guardado", action = "refrescarReserva")
})
@AutoSave(action = "buscarCargos", debounceMillis = 350)
@Service
@Scope("prototype")
public class ReservaOverview
    implements PostHydrationHandler, ToolbarSupplier, ActionHandler, ActionSupplier, PageWidthSupplier,
    io.mateu.uidl.interfaces.VisibilitySupplier {

  @Getter(AccessLevel.NONE) final StayQueries queries;
  @Getter(AccessLevel.NONE) final CheckInService checkIn;
  @Getter(AccessLevel.NONE) final CheckOutService checkOut;
  @Getter(AccessLevel.NONE) final NoShowService noShows;
  @Getter(AccessLevel.NONE) final RoomChangeService roomChange;
  @Getter(AccessLevel.NONE) final FolioService folios;
  @Getter(AccessLevel.NONE) final KardexService kardex;
  @Getter(AccessLevel.NONE) final IncidentService incidents;
  @Getter(AccessLevel.NONE) final RoomRepository rooms;
  @Getter(AccessLevel.NONE) final ChargeCatalogRepository chargeCatalog;
  @Getter(AccessLevel.NONE) final AddOnCatalogRepository addOnCatalog;
  @Getter(AccessLevel.NONE) final GuestNotices notices;

  public ReservaOverview(StayQueries queries, CheckInService checkIn, CheckOutService checkOut,
                         NoShowService noShows, RoomChangeService roomChange, FolioService folios,
                         KardexService kardex, IncidentService incidents, RoomRepository rooms,
                         ChargeCatalogRepository chargeCatalog, AddOnCatalogRepository addOnCatalog,
                         GuestNotices notices) {
    this.queries = queries;
    this.checkIn = checkIn;
    this.checkOut = checkOut;
    this.noShows = noShows;
    this.roomChange = roomChange;
    this.folios = folios;
    this.kardex = kardex;
    this.incidents = incidents;
    this.rooms = rooms;
    this.chargeCatalog = chargeCatalog;
    this.addOnCatalog = addOnCatalog;
    this.notices = notices;
  }

  /** El ancho de página según el estado: el foldout de llegada va a sangre; el general
   *  overview de la estancia (y la salida / modo check-out) van en FIXED. */
  @Override
  public PageWidthStyle pageWidth() {
    return queries.find(stayId).filter(s -> s.status() == StayStatus.ARRIVING).isPresent()
        ? PageWidthStyle.EDGE_TO_EDGE : PageWidthStyle.FIXED;
  }

  @Override
  public List<Action> actions(HttpRequest httpRequest) {
    return List.of(
        Action.builder().id("*").build(),
        Action.builder().id("opFirma").sse(true).build(),
        Action.builder().id("escanearPax").sse(true).build(),
        Action.builder().id("enviarTableta").sse(true).build());
  }

  @Hidden String stayId;

  // ── modo CHECK-OUT (plegado de la antigua /checkout/:id dentro de la 360) ────
  @Hidden boolean modoCheckout;

  // ── modo HABITACIÓN (cambiar la pre-asignada / upgrade, desde su tarjeta) ────
  @Hidden boolean modoHabitacion;

  // ── pax al que apunta la isla del documento (1 = huésped principal) ──────────
  @Hidden int paxSeleccionado = 1;

  // ── modos COBRO y EXTRAS + firma en curso (tablet) ───────────────────────────
  @Hidden boolean modoCobro;
  @Hidden boolean modoExtras;
  @Hidden boolean firmaEnviada;

  @Hidden String metodoPago = "card";
  @Hidden String cargoBusqueda;
  @Hidden String ultimaBusqueda;

  // ── «Cliente»: el pax cuyo reconocimiento se muestra ──────
  @Hidden int paxCliente = 1;

  /** Who each pax is in the chain; none in a test that builds the page by hand. */
  @Getter(AccessLevel.NONE) io.mateu.ecdemo1.frontoffice.application.Recognition recognition;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRecognition(io.mateu.ecdemo1.frontoffice.application.Recognition recognition) {
    this.recognition = recognition;
  }

  // ── header del huésped (por estado) ──────────────────────────────────────────
  @Section(value = "", frameless = true)
  @Label("")
  Callable<Component> header =
      () ->
          switch (stay().status()) {
            case ARRIVING -> GuestHeaders.arrivalHeader(stayId);
            case IN_HOUSE -> GuestHeaders.inHouseHeader(stayId);
            case DEPARTED -> GuestHeaders.departureHeader(stayId);
            case CANCELLED, NO_SHOW -> GuestHeaders.arrivalHeader(stayId);
          };

  // ── cuerpo: huéspedes + operativa en dos columnas; en el check-in, envuelto en un
  // foldout con la info accesoria del cliente (perfil) como panel lateral plegado ──────
  @Section(value = " ", frameless = true)
  @Label("")
  Callable<Component> cuerpo = this::cuerpo;

  // ── la estancia en los otros sistemas de la cadena: el cliente en Clientes, su contacto en
  // Salesforce, su perfil de Opera y la reserva del CRS — enlaces, en todos los estados ─────────
  @Section("En otros sistemas")
  @Label("")
  Callable<Component> otrosSistemas = () -> OtherSystems.of(stayId);

  // ── quién hizo qué con la reserva, y cuándo: la recepción, el agente y el CRS (el servicio de auditoría) ──
  @Section("Historial")
  @Label("")
  Callable<Component> historial = () -> io.mateu.ecdemo1.frontoffice.ui.common.ReservationHistory.of(stayId);

  /**
   * «En otros sistemas» e «Historial» no van en la llegada: en el check-in no interesan. Como
   * secciones de página solo van en la estancia y la salida.
   */
  @Override
  public boolean isHidden(String memberName, HttpRequest httpRequest) {
    if (!"otrosSistemas".equals(memberName) && !"historial".equals(memberName)) {
      return false;
    }
    return llegadaEnFoldout();
  }

  /** La llegada (fuera de los modos que la sustituyen) se pinta como foldout. */
  boolean llegadaEnFoldout() {
    return stayId != null && queries.find(stayId)
        .filter(s -> s.status() == StayStatus.ARRIVING && !modoCheckout)
        .isPresent();
  }

  // ── los paneles ──────────────────────────────────────────────────────────────

  HuespedesPanel huespedes() {
    return new HuespedesPanel(this);
  }

  LlegadaPanel llegada() {
    return new LlegadaPanel(this);
  }

  EstanciaPanel estancia() {
    return new EstanciaPanel(this);
  }

  ReservaDrawers drawers() {
    return new ReservaDrawers(this);
  }

  private Component cuerpo() {
    var stay = stay();
    if (stay.status() == StayStatus.IN_HOUSE && !modoCheckout) {
      // EN CASA: anatomía General Overview — el renderer monta el template nativo
      // (oj-sp-general-overview-page) con el slot MAIN (KPI + incidencias) primero
      // y el slot INFO (huéspedes + salida) como complementario; los fondos y el
      // ancho del info los pone el propio template
      return dosZonas(
          List.of(titulo("Estancia · " + estancia().balanceResumen()),
              io.mateu.ecdemo1.frontoffice.ui.checkin.ForcedCheckInViews.onTheStay(stayId),
              estancia().paraInHouse(stay)),
          huespedes().infoSecundaria(stay));
    }
    if (stay.status() != StayStatus.ARRIVING) {
      // salida / modo check-out: anatomía General Overview (la misma que la estancia
      // en casa) — zona ANCHA primero con su título de fold (el folio y el cobro) y
      // la info clave (salida + huéspedes) como zona complementaria estrecha
      var tituloMain = modoCheckout
          ? "Check-out · " + estancia().balanceResumen()
          : "Estancia · " + EstanciaPanel.cierre(stay);
      return dosZonas(
          List.of(titulo(tituloMain), estancia().avisosCheckout(stay), operativaPorEstado(stay),
              estancia().checkoutFolioPanel(),
              estancia().checkoutCargosPanel(), estancia().checkoutCobroPanel()),
          List.of(titulo("Información"),
              modoCheckout ? estancia().claveCheckout(stay) : huespedes().infoSalida(stay)));
    }
    // check-in / en casa (foldout, anatomía RDS): overview = rail de huéspedes;
    // panel ancho = la operativa (checklist de llegada o cockpit de estancia); y la
    // info accesoria del cliente (perfil) como TERCER panel, plegado
    return FoldoutLayout.builder()
        // el overview lleva su propio título de panel (no el de la página)
        .headerTitle("Huéspedes")
        .overview(VerticalLayout.builder()
            .style("width: 100%; gap: 1rem;")
            .content(List.of(huespedes().rail(stay, false), huespedes().cliente(stay)))
            .build())
        .panels(List.of(
            FoldoutPanel.builder()
                .id("operaciones")
                .title("Operaciones")
                // el contador vive en el header del panel: "N de 7" en llegada
                .subtitle(llegada().opsResumen(stay))
                .open(true)
                // 2 celdas fijas de 22rem + gap 40 + gutters 24 + 24px de holgura
                // anti-scrollbar (ver .mateu-grid-cell)
                .width("51rem")
                .content(operativaPorEstado(stay))
                .build(),
            FoldoutPanel.builder()
                .id("perfil")
                // título corto: "Perfil del cliente" no cabe en la cabecera Redwood a 17rem
                .title("Perfil")
                .open(false)
                // info accesoria: estrecha
                .width("14rem")
                .content(huespedes().perfilCliente())
                .build()))
        .build();
  }

  /** La zona ancha (62%) y la complementaria (38%) del general overview. */
  private static Component dosZonas(List<Component> main, List<Component> info) {
    return HorizontalLayout.builder()
        .style("width: 100%; gap: 1.5rem;")
        .wrap(true)
        .content(List.of(
            VerticalLayout.builder()
                .style("flex: 1 1 calc(62% - 1.5rem); min-width: min(20rem, 100%); gap: 1rem;")
                .content(main)
                .build(),
            VerticalLayout.builder()
                .style("flex: 1 1 calc(38% - 1.5rem); min-width: min(16rem, 100%); gap: .25rem;")
                .content(info)
                .build()))
        .build();
  }

  private static Component titulo(String text) {
    return Text.builder().text(text).container(TextContainer.h2).style("margin: 0;").build();
  }

  /** Lo que aplique según el estado (checklist de llegada, balance in-house, salida). */
  private Component operativaPorEstado(Stay stay) {
    return switch (stay.status()) {
      case ARRIVING -> llegada().paraLlegada(stay);
      case IN_HOUSE -> estancia().paraInHouse(stay);
      case DEPARTED, CANCELLED, NO_SHOW -> estancia().paraSalida(stay);
    };
  }

  // ── toolbar por estado ───────────────────────────────────────────────────────
  @Override
  public Collection<UserTrigger> toolbar() {
    if (modoCheckout) {
      return List.of(Button.builder().label("Volver a la reserva").actionId("volverReserva").build());
    }
    if (modoHabitacion || modoCobro || modoExtras) {
      return List.of(Button.builder().label("Volver a la reserva").actionId("volverHabitacion").build());
    }
    var stay = stay();
    return switch (stay.status()) {
      // siempre habilitado: con todo hecho confirma; si falta algo, lleva a completarlo (iniciarCheckin)
      // — un botón gris no dice qué falta
      case ARRIVING -> List.of(
          Button.builder().label(llegada().listo(stay) ? "Confirmar check-in" : "Completar check-in")
              .actionId("iniciarCheckin")
              .buttonStyle(ButtonStyle.primary)
              .build(),
          // the guest does it themselves on the lobby tablet (Civitfun, simulated)
          Button.builder().label("Check-in en tableta").actionId("enviarTableta").build(),
          Button.builder().label("Caja").actionId("abrirCaja").build(),
          empresaButton());
      case IN_HOUSE -> List.of(
          Button.builder().label("Check-out").actionId("irCheckout").buttonStyle(ButtonStyle.primary).build(),
          Button.builder().label("Añadir cargo").actionId("opCargos").build(),
          Button.builder().label("Cambiar habitación").actionId("opHabitacion").build(),
          Button.builder().label("Gestionar folio").actionId("gestionFolio").build(),
          Button.builder().label("Caja").actionId("abrirCaja").build(),
          empresaButton(),
          Button.builder().label("Mensaje huésped").actionId("mensajeHuesped").build(),
          Button.builder().label("Registrar petición").actionId("opPeticion").build(),
          Button.builder().label("Nueva incidencia").actionId("opIncidencia").build());
      case DEPARTED, CANCELLED, NO_SHOW -> List.of();
    };
  }

  @Override
  public boolean supportsAction(String actionId) {
    return List.of("iniciarCheckin", "irCheckout", "volverReserva", "mensajeHuesped",
            "opWifi", "opLlave", "opHabitacion", "elegirHabitacion", "upgrade360",
            "volverHabitacion", "escanearPax", "rellenarPax", "guardarPax", "refrescarReserva",
            "siguienteReserva", "volverListado",
            "opCobro", "metodoCobro", "confirmarCobro",
            "opCargos", "postearCargo", "resolverIncidencia", "lateCheckout",
            "gestionFolio", "opPeticion", "registrarPeticion",
            "opIncidencia", "crearIncidencia", "enviarMensaje",
            "opExtras", "extras360", "cerrarExtras", "opFirma", "opFirmaDone",
            "buscarCargos", "seleccionarCargo", "cambiarMetodo", "confirmPayment", "entendidoCheckout",
            "anularCargo", "comprobarHabitacion", "completarCheckin",
            // Mateu 392 refuses an action a component does not declare here («not an action»)
            "noShowPax", "confirmarNoShowPax", "cancelarNoShowPax", "guardarExtras",
            "clientePax", "abrirCaja", "enviarTableta",
            "opEmpresa", "cambiarEmpresa", "buscarEmpresas", "elegirEmpresa", "quitarEmpresa", "guardarEmpresa")
        .contains(actionId);
  }

  @Override
  public Object handleAction(String actionId, HttpRequest httpRequest) {
    return switch (actionId) {
      case "iniciarCheckin" -> iniciarCheckin();
      case "siguienteReserva" -> URI.create("/reservas/" + param(httpRequest, "_item"));
      case "volverListado" -> URI.create("/reservas?vista=LLEGADAS_HOY");
      // cobros, anticipos, recibos, proforma y crédito de la estancia
      case "abrirCaja" -> URI.create("/caja/" + stayId);
      case "enviarTableta" -> {
        if (tablet == null) {
          yield new Message("La tableta del lobby no está disponible");
        }
        // simulated, as the scanner: the guest does each step on the tablet; the progress says what they did
        var id = stayId;
        var pasos = io.mateu.ecdemo1.frontoffice.application.TabletCheckIn.STEPS;
        yield LongTask.create("Check-in en tableta (Civitfun)")
            .withProgressBar()
            .done("Check-in en tableta completado", "El huésped lo ha hecho todo en la tableta: entrega la llave")
            .closeAfter(2)
            .withCommand(UICommand.dispatchEvent("documento-escaneado"))
            .run(progress -> Flux.range(1, pasos.size())
                .delayElements(Duration.ofMillis(1100))
                .map(i -> progress.step(tablet.step(id, i), i / (double) pasos.size())));
      }
      case "irCheckout" -> {
        // un check-in forzado aún incompleto no sale: primero «Completar» (el servidor lo niega igual)
        var incompleto = io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.checkInStatus(stayId);
        if (incompleto.incomplete()) {
          yield List.of(new Message("⛔ Check-in incompleto: falta " + incompleto.missingText()
                  + ". Complétalo antes del check-out."),
              UICommand.navigateTo("/checkin/" + stayId));
        }
        modoCheckout = true;
        yield this;
      }
      case "completarCheckin" -> URI.create("/checkin/" + stayId);
      case "volverReserva" -> {
        modoCheckout = false;
        cargoBusqueda = null;
        ultimaBusqueda = null;
        yield this;
      }
      case "mensajeHuesped" -> drawers().mensaje();
      case "enviarMensaje" -> {
        var texto = drawerText(httpRequest, "mensajeTexto").trim();
        if (texto.isBlank()) {
          yield new Message("Escribe el mensaje antes de enviar");
        }
        yield List.of(new Message("Mensaje enviado a " + view().guest().name() + " — «" + texto + "»"),
            UICommand.closeModal());
      }
      case "noShowPax" -> {
        var pax = paxDe(httpRequest);
        if (!io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.ops(stayId).isNoShow(pax)) {
          // marcarlo se pregunta antes: si nadie más ha llegado, el CRS cancela la reserva con su cargo
          yield drawers().confirmarNoShow(pax, nameOf(pax));
        }
        yield noShowPax(pax);
      }
      case "confirmarNoShowPax" -> {
        var result = new ArrayList<Object>(List.of(UICommand.closeModal()));
        var done = noShowPax(paxDe(httpRequest));
        if (done instanceof List<?> list) {
          result.addAll(list);
        } else {
          result.add(done);
        }
        yield result;
      }
      case "cancelarNoShowPax" -> UICommand.closeModal();
      case "opWifi" -> {
        checkIn.wifiCreated(stayId);
        yield List.of(this, new Message("Tarjeta wifi creada — red HOTEL_GUEST · clave " + claveWifi()));
      }
      case "opLlave" -> {
        checkIn.keyEncoded(stayId);
        yield List.of(this, new Message("Llave / pulsera grabada — Hab " + stay().roomNumber()));
      }
      case "escanearPax" -> escanearPax(paxDe(httpRequest));
      case "rellenarPax" -> {
        paxSeleccionado = paxDe(httpRequest);
        yield drawerPax();
      }
      case "guardarPax" -> {
        // el kárdex como lo guarda el check-in, y lo que el huésped dijo reconoce al cliente
        var aviso = documentoView().saveKardex(stayId, paxSeleccionado, kardexDelDrawer(httpRequest));
        var nombre = nameOf(paxSeleccionado);
        // cierra el drawer emitiendo el evento al que está suscrita la página (refresco del rail)
        yield List.of(new Message(aviso != null ? aviso : "Kárdex guardado — " + nombre),
            UICommand.closeModal("cardex-guardado"));
      }
      case "refrescarReserva" -> this;
      case "clientePax" -> {
        paxCliente = (int) Double.parseDouble(param(httpRequest, "paxIndex"));
        yield this;
      }
      case "opEmpresa" -> drawerEmpresa();
      case "cambiarEmpresa" -> EmpresaPanel.busqueda(stayId, null, null, empresas().of(stayId).orElse(null));
      case "buscarEmpresas" -> {
        var texto = drawerText(httpRequest, "empresaBusqueda");
        yield EmpresaPanel.busqueda(stayId, texto, empresas().search(texto), empresas().of(stayId).orElse(null));
      }
      case "elegirEmpresa" -> {
        empresas().set(stayId, param(httpRequest, "_company"), DeskUser.name());
        var empresa = empresas().of(stayId).map(io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks.Company::name).orElse("");
        yield List.of(this, new Message("Se factura a " + empresa + " — enviado a Opera"), UICommand.closeModal());
      }
      case "quitarEmpresa" -> {
        empresas().set(stayId, null, DeskUser.name());
        yield List.of(this, new Message("Reserva sin empresa — enviado a Opera"), UICommand.closeModal());
      }
      case "guardarEmpresa" -> {
        var empresa = EmpresaPanel.posted(httpRequest.runActionRq().componentState());
        try {
          empresas().correct(stayId, empresa, DeskUser.name());
        } catch (RuntimeException e) {
          yield new Message("⛔ " + e.getMessage());
        }
        yield List.of(this, new Message("Datos de " + empresa.name() + " guardados en el ERP; Opera los recibe del ERP"),
            UICommand.closeModal());
      }
      case "opHabitacion" -> llegada().drawerHabitacion(stay());
      case "volverHabitacion" -> {
        modoHabitacion = false;
        modoCobro = false;
        modoExtras = false;
        yield this;
      }
      case "opCobro" -> {
        if (stay().status() == StayStatus.ARRIVING) {
          yield llegada().drawerCobro(stay());
        }
        modoCobro = true;
        yield this;
      }
      case "metodoCobro" -> {
        metodoPago = param(httpRequest, "_method");
        if (!modoCobro && stay().status() == StayStatus.ARRIVING) {
          // el picker vive en el drawer: refrescarlo en sitio (mismo Drawer.id), sin
          // tocar el host — el foldout ni se entera
          yield llegada().drawerCobro(stay());
        }
        yield this;
      }
      case "confirmarCobro" -> confirmarCobro(httpRequest);
      case "opCargos" -> drawers().cargos();
      case "gestionFolio" -> drawers().folio();
      case "opIncidencia" -> drawers().nuevaIncidencia();
      case "crearIncidencia" -> {
        var tipo = IncidentType.valueOf(param(httpRequest, "_item"));
        var incidencia = incidents.report(stayId, tipo, drawerText(httpRequest, "incTitulo"),
            drawerText(httpRequest, "incComentario"));
        yield List.of(this,
            new Message("Incidencia abierta — " + incidencia.title() + " (" + tipo.label() + ")"),
            UICommand.closeModal());
      }
      case "opPeticion" -> drawers().peticiones();
      case "registrarPeticion" -> {
        var peticion = param(httpRequest, "_item");
        if ("late-checkout".equals(peticion)) {
          yield List.of(this, lateCheckout(), UICommand.closeModal());
        }
        yield List.of(this,
            new Message("Petición registrada — housekeeping avisado (" + peticion + ")"),
            UICommand.closeModal());
      }
      case "postearCargo" -> {
        var refused = creditRefused(httpRequest);
        if (refused != null) {
          yield List.of(this, new Message(refused), UICommand.closeModal());
        }
        var item = folios.postCharge(stayId, param(httpRequest, "_item"), DeskUser.name()).orElse(null);
        if (item == null) {
          yield new Message("Cargo no encontrado: " + param(httpRequest, "_item"));
        }
        yield List.of(this,
            new Message("Cargo posteado — " + item.name() + " " + GuestHeaders.euros(item.price())),
            UICommand.closeModal());
      }
      case "anularCargo" -> {
        // «Anular» en el folio: la línea queda anulada (no cuenta) y Opera anula su posteo
        var lineId = param(httpRequest, "_item").replaceFirst("^linea-", "");
        var voided = folios.voidCharge(stayId, lineId, DeskUser.name()).orElse(null);
        if (voided == null) {
          yield new Message("Ese cargo no se puede anular (el alojamiento es de Opera)");
        }
        yield List.of(this, new Message("Cargo anulado — " + voided.concept() + " " + GuestHeaders.euros(voided.amount())
            + ". Se anula también en el folio de Opera."), UICommand.closeModal());
      }
      case "comprobarHabitacion" -> {
        var readiness = io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.refreshRoom(stay().roomNumber());
        yield List.of(this, new Message(!readiness.known() ? "Opera no responde: " + readiness.reason()
            : readiness.ready() ? "Habitación " + readiness.roomNumber() + " lista en Opera (" + readiness.state() + ")"
            : "Habitación " + readiness.roomNumber() + " aún no lista: " + readiness.reason()));
      }
      case "resolverIncidencia" -> {
        incidents.resolve(stayId, param(httpRequest, "_item").replaceFirst("^inc-", ""));
        yield List.of(this, new Message("Incidencia resuelta"));
      }
      case "lateCheckout" -> List.of(this, lateCheckout());
      case "opExtras" -> {
        if (stay().status() == StayStatus.ARRIVING) {
          yield llegada().drawerExtras(stay());
        }
        modoExtras = true;
        yield this;
      }
      case "extras360" -> {
        checkIn.addOnToggled(stayId, param(httpRequest, "_item"),
            Boolean.parseBoolean(param(httpRequest, "_added")));
        yield this;
      }
      case "guardarExtras" -> {
        // los switches del drawer viajan en el componentState (addon_<id> = true/false)
        var estado = httpRequest.runActionRq().componentState();
        var elegidos = new HashMap<String, Boolean>();
        for (var item : addOnCatalog.findAll()) {
          var campo = "addon_" + item.id();
          if (estado != null && estado.containsKey(campo)) {
            elegidos.put(item.id(), Boolean.parseBoolean(String.valueOf(estado.get(campo))));
          }
        }
        var stay = checkIn.extrasChosen(stayId, elegidos);
        yield List.of(this, new Message(LlegadaPanel.extrasHecha(stay)), UICommand.closeModal());
      }
      case "cerrarExtras" -> {
        checkIn.extrasClosed(stayId);
        if (!modoExtras && stay().status() == StayStatus.ARRIVING) {
          yield List.of(this, new Message(LlegadaPanel.extrasHecha(stay())), UICommand.closeModal());
        }
        modoExtras = false;
        yield List.of(this, new Message(LlegadaPanel.extrasHecha(stay())));
      }
      case "opFirma" ->
          // SSE: diálogo de progreso mientras la tablet trabaja; al cerrar, el evento
          // dispara opFirmaDone vía la suscripción de la clase
          LongTask.create("Firma en tablet")
              .withProgressBar()
              .done("Firma capturada", "Registro firmado por el huésped")
              .closeAfter(1)
              .withCommand(UICommand.dispatchEvent("firma-capturada-360"))
              .run(progress -> Flux.range(1, 3)
                  .delayElements(Duration.ofMillis(1200))
                  .map(i -> progress.step(PASOS_FIRMA[i - 1], i / 3.0)));
      case "opFirmaDone" -> {
        firmaEnviada = false;
        checkIn.registrationSigned(stayId, DeskUser.name());
        yield List.of(this, new Message("Firma capturada — registro firmado por el huésped"));
      }
      case "elegirHabitacion" -> {
        var number = param(httpRequest, "_item");
        var room = roomChange.changeRoom(stayId, number).orElse(null);
        if (room == null) {
          yield new Message("La habitación " + number + " no está disponible");
        }
        yield trasCambioDeHabitacion(
            new Message("Habitación cambiada — Hab " + number + " (" + room.typeLabel() + ")"));
      }
      case "upgrade360" -> {
        if (roomChange.changeRoom(stayId, LlegadaPanel.SUITE_UPGRADE).isEmpty()) {
          yield new Message("La suite del upgrade no está disponible");
        }
        yield trasCambioDeHabitacion(
            new Message("Upgrade aplicado — Master Oceanfront Suite (Hab 1401, + € 65 / noche)"));
      }
      case "buscarCargos" -> {
        if (Objects.equals(cargoBusqueda, ultimaBusqueda)) {
          yield null; // otro campo disparó el auto-save — sin re-render
        }
        ultimaBusqueda = cargoBusqueda;
        yield this;
      }
      case "seleccionarCargo" -> {
        var refused = creditRefused(httpRequest);
        if (refused != null) {
          yield List.of(this, new Message(refused), UICommand.closeModal());
        }
        var item = folios.postCharge(stayId, param(httpRequest, "_item"), DeskUser.name()).orElse(null);
        if (item == null) {
          yield new Message("Cargo no encontrado: " + param(httpRequest, "_item"));
        }
        cargoBusqueda = null;
        ultimaBusqueda = null;
        yield List.of(this,
            new Message("Cargo posteado — " + item.name() + " " + GuestHeaders.euros(item.price())));
      }
      case "cambiarMetodo" -> {
        metodoPago = param(httpRequest, "_method");
        yield this;
      }
      case "confirmPayment" -> confirmPayment(httpRequest);
      case "entendidoCheckout" -> {
        // «Entendido»: recepción ha leído los avisos de la salida (kárdex rechazado o pendiente de
        // Salesforce, avisos de check-out) tal como están ahora; queda auditado
        notices.acknowledgeCheckOut(stayId, DeskUser.name(), null);
        yield List.of(this, new Message("Avisos de salida confirmados — ya se puede cobrar y cerrar el check-out"));
      }
      default -> null;
    };
  }

  /**
   * Con todo resuelto (las operaciones de la llegada, los datos de registro, los avisos) no hay nada
   * que preguntar: check-in directo y la 360 se re-renderiza ya in-house, sin pasar por el wizard. Si
   * falta algo que el wizard hace, el wizard; si solo falta lo que hace una tarjeta (la wifi), se dice
   * cuál y se queda aquí.
   */
  private Object iniciarCheckin() {
    var stay = stay();
    var ops = llegada().operaciones(stay);
    // un aviso bloqueante sin leer se lee en el primer paso del wizard
    switch (LlegadaPanel.siguiente(ops, queries.readyForDirectCheckIn(stay), notices.checkInAcknowledged(stay))) {
      case WIZARD -> {
        return URI.create("/checkin/" + stayId);
      }
      case TARJETA -> {
        return List.of(this, new Message("Falta: " + LlegadaPanel.soloEnTarjeta(ops)
            + ". Hazlo en su tarjeta y vuelve a pulsar «Confirmar check-in»."));
      }
      case DIRECTO -> {
      }
    }
    Stay checkedIn;
    try {
      checkedIn = checkIn.checkIn(stayId, null, List.of(), DeskUser.name());
    } catch (GuestNotices.NotAcknowledged
             | io.mateu.ecdemo1.frontoffice.application.IncompleteCheckIns.CheckInIncomplete e) {
      return URI.create("/checkin/" + stayId);
    }
    // la habitación que no está lista en Opera (XMAR: sin inspeccionar) puede hacer que Opera lo rechace
    var lista = io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.roomReadiness(checkedIn.roomNumber());
    var mensaje = new Message("✅ Check-in completado — " + view().guest().name() + " · Hab " + checkedIn.roomNumber()
        + (lista.known() && !lista.ready() ? " — ojo: en Opera aún no está lista (" + lista.reason()
            + "), Opera puede rechazarla" : ""));
    // solo una reserva DE GRUPO propone seguir con la siguiente llegada del grupo
    // (simulado: mismo grupo = primera palabra de la agencia); sin grupo o sin más
    // llegadas pendientes → directamente de vuelta al listado
    var siguiente = queries.nextArrivalOfGroup(checkedIn);
    if (siguiente.isPresent()) {
      return List.of(this, mensaje, drawers().siguienteDelGrupo(siguiente.get()));
    }
    // de vuelta al listado OPERATIVO: las llegadas de hoy, no todas las reservas
    return List.of(mensaje, UICommand.navigateTo("/reservas?vista=LLEGADAS_HOY"));
  }

  /** SSE: diálogo de progreso (LongTask) mientras el escáner trabaja; al cerrar, el comando dispara el evento que refresca la 360. */
  private Object escanearPax(int pax) {
    var nombre = nameOf(pax);
    var idEstancia = stayId;
    return LongTask.create("Escaneando el documento de " + nombre + "…")
        .withProgressBar()
        .done("Documento verificado", "Identidad leída del documento")
        .closeAfter(1)
        .withCommand(UICommand.dispatchEvent("documento-escaneado"))
        .run(progress -> Flux.range(1, 4)
            .delayElements(Duration.ofMillis(450))
            .map(i -> {
              if (i == 4) {
                kardex.scanned(idEstancia, pax);
              }
              return progress.step(PASOS_ESCANEO[i - 1], i / 4.0);
            }));
  }

  /**
   * El formulario del cardex abre en un DRAWER, precargado con el cardex actual (sirve tanto para
   * registrar como para validar/corregir). Las acciones del drawer postean SU estado (initialData +
   * borrador), no el del host — por eso stayId y paxSeleccionado viajan en el initialData.
   */
  private Object drawerPax() {
    var view = view();
    var guest = view.guest();
    var companion = paxSeleccionado <= 1 ? null : view.stay().companionAt(paxSeleccionado);
    var completo = paxSeleccionado <= 1 ? guest.identityComplete() : companion != null && companion.identityComplete();
    var editor = documentoView().kardexOf(stayId, paxSeleccionado);
    var initialData = new HashMap<String, Object>();
    initialData.put("stayId", stayId);
    initialData.put("paxSeleccionado", paxSeleccionado);
    KARDEX_JSON.convertValue(editor, java.util.Map.class).forEach((campo, valor) ->
        initialData.put(HuespedesPanel.PREFIJO_KARDEX + campo, valor == null ? "" : valor));
    return io.mateu.uidl.data.Drawer.builder()
        .headerTitle((completo ? "Validar / editar huésped " : "Registrar huésped ")
            + paxSeleccionado + " — " + nameOf(paxSeleccionado))
        .width("44rem")
        .content(huespedes().formularioPax(editor))
        .initialData(initialData)
        .build();
  }

  static final tools.jackson.databind.json.JsonMapper KARDEX_JSON = tools.jackson.databind.json.JsonMapper.builder()
      .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** The check-in's kárdex editor of the pax, for its form here (a prototype bean, asked of Mateu's provider). */
  private DocumentoView documentoView() {
    return io.mateu.uidl.di.MateuBeanProvider.getBean(DocumentoView.class);
  }

  /** The kárdex the drawer posted: its {@code k_} fields, as the check-in's editor. */
  static DocumentoView.DocumentoEditor kardexDelDrawer(HttpRequest httpRequest) {
    var estado = httpRequest.runActionRq().componentState();
    var campos = new HashMap<String, Object>();
    if (estado != null) {
      estado.forEach((clave, valor) -> {
        if (clave.startsWith(HuespedesPanel.PREFIJO_KARDEX) && valor != null && !"".equals(valor)) {
          var campo = clave.substring(HuespedesPanel.PREFIJO_KARDEX.length());
          // a date field may post a time too
          campos.put(campo, valor instanceof String texto && texto.matches("\\d{4}-\\d{2}-\\d{2}T.*")
              ? texto.substring(0, 10) : valor);
        }
      });
    }
    return KARDEX_JSON.convertValue(campos, DocumentoView.DocumentoEditor.class);
  }

  /** The header's EMPRESA, chosen or corrected: its drawer. */
  private static Button empresaButton() {
    return Button.builder().id("empresa").label("✎ Empresa").actionId("opEmpresa").build();
  }

  /** The company's drawer: its data when the stay has one, else the search to choose it. */
  private Object drawerEmpresa() {
    var enLaReserva = empresas().of(stayId).filter(c -> c.code() != null).orElse(null);
    return enLaReserva == null ? EmpresaPanel.busqueda(stayId, null, null, null)
        : EmpresaPanel.datos(stayId, empresas().details(stayId).orElse(null), enLaReserva);
  }

  /** The stays' companies — asked of Mateu's bean provider: a page's non-final service fields are walked as state. */
  private static io.mateu.ecdemo1.frontoffice.application.StayCompanies empresas() {
    return io.mateu.uidl.di.MateuBeanProvider.getBean(io.mateu.ecdemo1.frontoffice.application.StayCompanies.class);
  }

  private Object confirmarCobro(HttpRequest httpRequest) {
    var params = httpRequest.runActionRq().parameters();
    var method = params != null && params.get("_method") != null ? String.valueOf(params.get("_method")) : metodoPago;
    var stay = stay();
    var texto = switch (method) {
      case "points" -> "Cobro con puntos — " + GuestHeaders.points(view().guest()) + " pts aplicados a "
          + GuestHeaders.euros(stay.total());
      case "cash" -> "Preautorización registrada — " + GuestHeaders.euros(stay.total()) + " en efectivo a la llegada";
      default -> "Preautorización completada — " + GuestHeaders.euros(stay.total()) + " en la tarjeta del huésped";
    };
    checkIn.paymentTaken(stayId, method, stay.total());
    if (!modoCobro && stay.status() == StayStatus.ARRIVING) {
      // desde el drawer: cerrar + repintar el host (el foldout se actualiza in situ)
      return List.of(this, new Message(texto), UICommand.closeModal());
    }
    modoCobro = false;
    return List.of(this, new Message(texto));
  }

  private Object confirmPayment(HttpRequest httpRequest) {
    var params = httpRequest.runActionRq().parameters();
    var method = params != null && params.get("_method") != null ? String.valueOf(params.get("_method")) : "card";
    var methodLabel = switch (method) {
      case "cash" -> "Efectivo";
      case "points" -> "Puntos";
      default -> "Tarjeta";
    };
    var view = view();
    var due = porCobrar();
    var total = GuestHeaders.euros(due);
    // what is still owed is taken at the till, with its receipt — the advances and payments already taken
    // during the stay are not asked again
    if (cashier != null && due.signum() > 0) {
      var payment = cashier.take(stayId, io.mateu.ecdemo1.frontoffice.domain.cashier.Payment.Kind.PAYMENT,
          switch (method) {
            case "cash" -> io.mateu.ecdemo1.frontoffice.domain.cashier.Payment.Method.CASH;
            case "points" -> io.mateu.ecdemo1.frontoffice.domain.cashier.Payment.Method.MANUAL;
            default -> io.mateu.ecdemo1.frontoffice.domain.cashier.Payment.Method.CARD_PINPAD;
          }, due, null, "points".equals(method) ? "Puntos Riu Class" : "Check-out", DeskUser.name());
      if (payment.status() == io.mateu.ecdemo1.frontoffice.domain.cashier.Payment.Status.DECLINED) {
        return List.of(this, new Message("⛔ Tarjeta denegada por el datáfono: " + total
            + " sin cobrar. Prueba otra tarjeta u otra forma de pago (o «Caja»)."));
      }
    }
    try {
      checkOut.checkOut(stayId, DeskUser.name());
    } catch (GuestNotices.NotAcknowledged e) {
      return List.of(this, new Message("⛔ " + e.getMessage()));
    } catch (io.mateu.ecdemo1.frontoffice.application.IncompleteCheckIns.CheckInIncomplete e) {
      modoCheckout = false;
      return List.of(new Message("⛔ " + e.getMessage()), UICommand.navigateTo("/checkin/" + stayId));
    }
    modoCheckout = false;
    return List.of(
        this,
        new Message("Cobro confirmado — " + total + " (" + methodLabel + ")"),
        new PageBanner(BannerTheme.SUCCESS, "Check-out completado",
            view.guest().name() + " · Hab " + view.stay().roomNumber() + " · Cobrados " + total + " con "
                + methodLabel + "."));
  }

  private Message lateCheckout() {
    return folios.contractLateCheckOut(stayId, DeskUser.name())
        ? new Message("Late check-out contratado — salida a las 15:00 (+ € 50,00)")
        : new Message("El late check-out ya estaba contratado — salida a las 15:00");
  }

  /** Sin modoHabitacion la selección vino del DRAWER (llegada o in-house): cerrarlo. */
  private Object trasCambioDeHabitacion(Message mensaje) {
    if (!modoHabitacion) {
      return List.of(this, mensaje, UICommand.closeModal());
    }
    modoHabitacion = false;
    return List.of(this, mensaje);
  }

  // ── ciclo de vida / helpers ──────────────────────────────────────────────────
  @Override
  public void onHydrated(HttpRequest httpRequest) {
    if (stayId == null || stayId.isBlank()) {
      stayId = GuestHeaders.idFromRoute(httpRequest, "reservas");
    }
  }

  /** The stay with its guest and folio — read once per request (the repositories cache it). */
  StayView view() {
    return queries.view(stayId);
  }

  Stay stay() {
    return queries.stay(stayId);
  }

  /** The pax's display name: the guest, a companion, or its pending slot. */
  String nameOf(int pax) {
    var view = view();
    if (pax <= 1) {
      return view.guest().name();
    }
    var companion = view.stay().companionAt(pax);
    return companion != null ? companion.name() : "Huésped " + pax;
  }

  /** Marca (o revierte) el no show de un pax, ya confirmado. */
  private Object noShowPax(int pax) {
    var outcome = noShows.paxToggled(stayId, pax);
    var nombre = nameOf(pax);
    if (outcome.nobodyArrived()) {
      // Nadie de la reserva ha llegado: es un no show de la reserva, y lo decide el CRS
      // (HLA F006) — la cancela con su cargo, y el resultado vuelve aquí y a Opera.
      return List.of(this, new Message("No show registrado — " + nombre + ". " + outcome.crsNotice()));
    }
    return List.of(this, new Message(outcome.noShow()
        ? "No show registrado — " + nombre
        : "No show revertido — " + nombre));
  }

  /** El pax de la fila pulsada ({_item} numérico de la lista de huéspedes). */
  private static int paxDe(HttpRequest httpRequest) {
    return (int) Double.parseDouble(param(httpRequest, "_item"));
  }

  /** The lobby tablet's self check-in; none in a test that does not wire it. */
  @Getter(AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore
  transient io.mateu.ecdemo1.frontoffice.application.TabletCheckIn tablet;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTablet(io.mateu.ecdemo1.frontoffice.application.TabletCheckIn tablet) {
    this.tablet = tablet;
  }

  /** What the check-out still has to take: the account's balance due (the folio's, without the cashier). */
  java.math.BigDecimal porCobrar() {
    if (cashier != null) {
      var due = cashier.account(stayId).due();
      return due.signum() > 0 ? due : java.math.BigDecimal.ZERO;
    }
    return GuestHeaders.balance(view().folio());
  }

  /** «⛔ Crédito cancelado…» when the stay's credit was cancelled: nothing goes on the room's account. */
  private String creditRefused(HttpRequest httpRequest) {
    if (cashier == null) {
      return null;
    }
    try {
      cashier.checkCharge(stayId, null);
      return null;
    } catch (io.mateu.ecdemo1.frontoffice.application.Cashier.Refused e) {
      return "⛔ " + e.getMessage();
    }
  }

  /** The stay's cashiering; none in a test that does not wire it. */
  @Getter(AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore
  transient io.mateu.ecdemo1.frontoffice.application.Cashier cashier;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCashier(io.mateu.ecdemo1.frontoffice.application.Cashier cashier) {
    this.cashier = cashier;
  }

  private static String param(HttpRequest httpRequest, String name) {
    return String.valueOf(httpRequest.runActionRq().parameters().get(name));
  }

  /** A field of the drawer that posted the action (its own state, not the page's); blank if none. */
  private static String drawerText(HttpRequest httpRequest, String field) {
    var estado = httpRequest.runActionRq().componentState();
    return estado != null && estado.get(field) != null ? String.valueOf(estado.get(field)) : "";
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }


  private static String orBlank(String value) {
    return value == null ? "" : value;
  }

  /** A memorable per-stay wifi key for the demo toast. */
  private String claveWifi() {
    return "HG-" + Math.abs(stayId.hashCode() % 9000 + 1000);
  }

  private static final String[] PASOS_ESCANEO = {
    "Encendiendo el escáner…", "Leyendo el documento…", "Extrayendo los datos…", "Verificando la identidad…"
  };

  private static final String[] PASOS_FIRMA = {
    "Enviando el registro a la tablet…", "Esperando la firma del huésped…", "Recibiendo la firma…"
  };
}
