package io.mateu.ecdemo1.frontoffice.ui.reservas;

import io.mateu.ecdemo1.frontoffice.domain.stay.SelectedAddOn;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.ecdemo1.frontoffice.ui.checkin.HabitacionStep;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.uidl.data.AddOn;
import io.mateu.uidl.data.AddOnPicker;
import io.mateu.uidl.data.Badge;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.ButtonStyle;
import io.mateu.uidl.data.Drawer;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FormField;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.OfferCard;
import io.mateu.uidl.data.PaymentMethod;
import io.mateu.uidl.data.PaymentPicker;
import io.mateu.uidl.data.ResourceGrid;
import io.mateu.uidl.data.StatusItem;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.TextContainer;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The arrival side of the Reserva 360: the check-in operations checklist, and what each of its
 * operations opens — the room chooser (with the upgrade offer), the payment and the ancillaries,
 * in the page or in a drawer.
 */
final class LlegadaPanel {

  /** The suite the upgrade offer proposes. */
  static final String SUITE_UPGRADE = "1401";

  private final ReservaOverview r;

  LlegadaPanel(ReservaOverview r) {
    this.r = r;
  }

  /** One row of the check-in operations checklist. */
  record Op(
      String id, String icon, String title, String pendiente, String hecha,
      boolean done, String actionLabel, String actionId, String actionIcon,
      String actionLabel2, String actionId2, String actionIcon2, String status, String statusColor) {

    Op(String id, String icon, String title, String pendiente, String hecha,
       boolean done, String actionLabel, String actionId, String actionIcon) {
      this(id, icon, title, pendiente, hecha, done, actionLabel, actionId, actionIcon, null, null, null, null, null);
    }

    Op(String id, String icon, String title, String pendiente, String hecha,
       boolean done, String actionLabel, String actionId, String actionIcon,
       String actionLabel2, String actionId2, String actionIcon2) {
      this(id, icon, title, pendiente, hecha, done, actionLabel, actionId, actionIcon, actionLabel2, actionId2,
          actionIcon2, null, null);
    }
  }

  /** "N de M" — operaciones de check-in completadas, para el header del panel. */
  String opsResumen(Stay stay) {
    var lista = operaciones(stay);
    var hechas = lista.stream().filter(Op::done).count();
    return hechas + " de " + lista.size();
  }

  /** Every operation done — the pax registered (no-shows aside) and the tasks — so it can be confirmed. */
  boolean listo(Stay stay) {
    return operaciones(stay).stream().allMatch(Op::done);
  }

  /** The operations the check-in wizard does itself; the others are done only on their card here. */
  static final java.util.Set<String> EN_EL_WIZARD =
      java.util.Set.of("documentos", "habitacion", "firma", "cobro", "llave", "extras");

  /** Where «check-in» goes: straight in, to the wizard, or back to a card the wizard has not got. */
  enum Siguiente { DIRECTO, WIZARD, TARJETA }

  /**
   * What the check-in button does with what is missing. Anything the wizard can do, or the service's
   * own checks (the registration rules' data, a blocking notice unread), opens the wizard; what only a
   * card does (the wifi card) is asked for on the card — the wizard would be another dead end.
   */
  static Siguiente siguiente(List<Op> ops, boolean listoParaDirecto, boolean avisosLeidos) {
    var pendientes = ops.stream().filter(op -> !op.done()).toList();
    if (!listoParaDirecto || !avisosLeidos || pendientes.stream().anyMatch(op -> EN_EL_WIZARD.contains(op.id()))) {
      return Siguiente.WIZARD;
    }
    return pendientes.isEmpty() ? Siguiente.DIRECTO : Siguiente.TARJETA;
  }

  /** What is missing that only its card does, for the desk: «Tarjeta wifi («Crear»)». */
  static String soloEnTarjeta(List<Op> ops) {
    return ops.stream().filter(op -> !op.done() && !EN_EL_WIZARD.contains(op.id()))
        .map(op -> op.title() + (op.actionLabel() == null ? "" : " («" + op.actionLabel() + "»)"))
        .collect(Collectors.joining(", "));
  }

  /** The check-in operations of an arriving stay — completed and pending, with quick actions. */
  List<Op> operaciones(Stay stay) {
    var ops = r.queries.ops(stay.id());
    var faltan = r.queries.pendingPax(stay);
    // asignada ⇒ tarea completada (la inspección es detalle de housekeeping, se muestra como info)
    var habitacionLista = stay.hasRoom();
    return List.of(
        new Op("documentos", "🪪", "Datos de huéspedes",
            "Falta la documentación de " + faltan + " pax — escaneo de documento en el check-in",
            "Documentación de los " + stay.pax() + " pax completa",
            faltan == 0, "Completar", "iniciarCheckin", null),
        new Op("habitacion", "🛏️",
            habitacionLista ? "Habitación " + stay.roomNumber() : "Habitación",
            "Sin habitación asignada — elegir una para la estancia",
            stay.roomType() + " asignada · " + listaEnOpera(stay),
            habitacionLista, "Cambiar", "opHabitacion", "vaadin:exchange",
            // preguntar otra vez a Opera si está lista: en la tarjeta, que es donde se dice
            habitacionLista ? "Comprobar" : null, habitacionLista ? "comprobarHabitacion" : null, "vaadin:refresh",
            estadoHabitacion(stay)[0], estadoHabitacion(stay)[1]),
        new Op("wifi", "📶", "Tarjeta wifi",
            "Crear las credenciales de acceso del huésped",
            "Credenciales creadas y entregadas",
            ops.wifi(), "Crear", "opWifi", "vaadin:wifi"),
        new Op("llave", "🔑", "Llave / pulsera",
            habitacionLista ? "Grabar la llave o pulsera de la Hab " + stay.roomNumber()
                : "Grabar la llave o pulsera — primero, asignar la habitación",
            "Llave / pulsera grabada",
            ops.llave(), "Grabar", "opLlave", "vaadin:key"),
        new Op("firma", "✍️", "Firma del registro",
            r.firmaEnviada
                ? "Enviada a la tablet · esperando la firma del huésped…"
                : "Enviar el registro a la tablet para su firma",
            "Firmada por el huésped en la tablet",
            ops.firma(), r.firmaEnviada ? null : "Enviar a tablet", "opFirma", "vaadin:pen"),
        new Op("cobro", "💳", "Cobro / preautorización",
            "Preautorizar " + GuestHeaders.euros(stay.total()) + " — tarjeta, efectivo o puntos",
            "Preautorización completada",
            ops.cobro(), "Cobrar", "opCobro", "vaadin:credit-card"),
        new Op("extras", "🎁", "Ancillaries",
            "Ofrecer los extras opcionales de la estancia",
            extrasHecha(stay),
            ops.extras(), "Elegir", "opExtras", "vaadin:gift"));
  }

  /** «lista en Opera» o por qué no — lo que dice Opera ahora de la habitación asignada (unos segundos en caché). */
  static String listaEnOpera(Stay stay) {
    var readiness = io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.roomReadiness(stay.roomNumber());
    if (!readiness.known()) {
      return "estado de Opera no disponible";
    }
    return readiness.ready() ? "lista en Opera (" + readiness.state() + ")"
        : "aún no lista en Opera: " + readiness.reason();
  }

  /**
   * El badge de la tarjeta de la habitación asignada, según Opera: «No lista» en rojo si no la da por
   * lista (libre e inspeccionada, lo que XMAR exige para asignarla), «Sin confirmar» si aún no ha dicho
   * nada; lista, o sin habitación, el de siempre (null). El porqué va en la descripción de la tarjeta, y
   * «Comprobar» en su acción: sin aviso aparte encima de las operaciones.
   */
  static String[] estadoHabitacion(Stay stay) {
    if (!stay.hasRoom()) {
      return new String[] {null, null};
    }
    var readiness = io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.roomReadiness(stay.roomNumber());
    if (!readiness.known()) {
      return new String[] {"Sin confirmar", "warning"};
    }
    return readiness.ready() ? new String[] {null, null} : new String[] {"No lista", "error"};
  }

  /**
   * Los avisos de la reserva y de su agencia para preparar la llegada y para el check-in (los de cada
   * huésped van bajo su fila, en la sección de huéspedes). Nada, si no hay ninguno.
   */
  Component avisosDeLaReserva(Stay stay) {
    var avisos = r.notices.forStay(stay, io.mateu.ecdemo1.frontoffice.domain.notice.Notice.Moment.PRE_ARRIVAL,
        io.mateu.ecdemo1.frontoffice.domain.notice.Notice.Moment.CHECK_IN).stream()
        .filter(p -> !p.onPax()).toList();
    return io.mateu.ecdemo1.frontoffice.ui.common.NoticeItems.block("Avisos de la reserva y de su agencia", avisos);
  }

  /** Texto de la operación de extras hecha, con lo contratado. */
  static String extrasHecha(Stay stay) {
    var n = stay.addOns().size();
    return n == 0 ? "Selección cerrada — sin extras" : "Selección cerrada — " + n + " extras";
  }

  Component paraLlegada(Stay stay) {
    if (r.modoHabitacion) {
      return elegirHabitacion(stay, true);
    }
    if (r.modoCobro) {
      return panelCobro(stay);
    }
    if (r.modoExtras) {
      return panelExtras(stay);
    }
    // la operación "datos de huéspedes" NO lleva tarjeta: la sección de huéspedes de
    // arriba ya muestra ese estado por pax (sí cuenta en el contador del header)
    var tarjetas = operaciones(stay).stream().filter(op -> !"documentos".equals(op.id())).toList();
    var checklist =
        StatusList.builder()
            .compact(true)
            .frameless(true)
            // cockpit a DOS columnas: dentro del panel ancho (44rem) las 6 tarjetas
            // quedan en 3 filas y se ven todas sin scroll interno
            .columns(2)
            .style("width: 100%;")
            .items(tarjetas.stream()
                .map(op -> {
                  // una operación hecha CONSERVA su acción: el recepcionista puede repetirla
                  // (regrabar una llave defectuosa, repetir la firma, cambiar de habitación…)
                  var conAccion = op.actionLabel() != null;
                  return StatusItem.builder()
                      .id(op.id())
                      .avatar(op.icon())
                      .title(op.title())
                      .description(op.done() ? op.hecha() : op.pendiente())
                      .status(op.status() != null ? op.status() : op.done() ? "✓ Hecha" : "Pendiente")
                      .statusColor(op.statusColor() != null ? op.statusColor() : op.done() ? "success" : "warning")
                      .actionLabel(conAccion ? op.actionLabel() : null)
                      .actionId(conAccion ? op.actionId() : null)
                      .actionIcon(conAccion ? op.actionIcon() : null)
                      .actionLabel2(op.actionLabel2())
                      .actionId2(op.actionId2())
                      .actionIcon2(op.actionLabel2() != null ? op.actionIcon2() : null)
                      .build();
                })
                .toList())
            .build();
    return VerticalLayout.builder()
        .content(List.of(avisosDeLaReserva(stay), checklist))
        .style("width: 100%; gap: .5rem;")
        .build();
  }

  /** La cuadrícula de disponibles + la oferta de upgrade — elegir una asigna la habitación. */
  Component elegirHabitacion(Stay stay, boolean conTitulo) {
    var guest = r.view().guest();
    var content = new ArrayList<Component>();
    if (conTitulo) {
      content.add(Text.builder().text("Elegir habitación").container(TextContainer.h3).style("margin: 0;").build());
    }
    content.add(HorizontalLayout.builder()
        .spacing(true).wrap(true)
        .content(guest.preferences().stream()
            .map(pref -> (Component) Badge.builder().text(pref.text()).pill(true).build())
            .toList())
        .build());
    content.add(ResourceGrid.builder()
        .style("width: 100%;")
        .actionId("elegirHabitacion")
        .columns(4)
        .recommendedLabel("RECOMENDADA")
        // The PMS's rooms of the stay's type, as Opera has them now (the check-in assigns it there).
        .items(io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.roomsFor(stay, 12).stream()
            .map(room -> HabitacionStep.item(room, stay.roomNumber(), stay.roomNumber()))
            .toList())
        .build());
    var upgrade = r.rooms.findByNumber(SUITE_UPGRADE).orElse(null);
    content.add(HorizontalLayout.builder()
        .spacing(true).wrap(true)
        .content(List.of(
            OfferCard.builder()
                .id("asignada")
                .style("flex: 1 1 340px; min-width: 320px;")
                .tag("HABITACIÓN ASIGNADA")
                .title(stay.roomType())
                .subtitle("Hab. " + stay.roomNumber())
                .features(List.of("42 m²", "Vista mar lateral", "Cama King", "Balcón"))
                .current(true)
                .currentLabel("✓ Incluida en tu reserva")
                .build(),
            OfferCard.builder()
                .id("upgrade")
                .style("flex: 1 1 340px; min-width: 320px;")
                .tag("UPGRADE DISPONIBLE")
                .title("Master Oceanfront Suite")
                .subtitle("Hab. 1401 · Planta 14 · Primera línea")
                .features(List.of("68 m²", "Vista mar frontal", "Terraza + jacuzzi", "Sofá lounge"))
                .priceLabel("+ € 65 / noche")
                .actionLabel("Mejorar a esta habitación")
                .actionId("upgrade360")
                .added(upgrade != null && !upgrade.assignable())
                .addedLabel("✓ Upgrade aplicado")
                .build()))
        .build());
    return VerticalLayout.builder().content(content).style("width: 100%; gap: 1rem;").build();
  }

  /** Modo cobro: preautorización con tarjeta / efectivo / puntos de fidelidad. */
  Component panelCobro(Stay stay) {
    var guest = r.view().guest();
    var contenido = new ArrayList<Component>();
    contenido.add(Text.builder().text("Cobro / preautorización").container(TextContainer.h3)
        .style("margin: 0;").build());
    contenido.add(PaymentPicker.builder()
        .actionId("confirmarCobro")
        .methodActionId("metodoCobro")
        .methods(List.of(
            PaymentMethod.builder().id("card").label("Tarjeta").build(),
            PaymentMethod.builder().id("cash").label("Efectivo").build(),
            PaymentMethod.builder().id("points")
                .label("Puntos (" + GuestHeaders.points(guest) + ")").build()))
        .selected(r.metodoPago)
        .contextLabel("TOTAL RESERVA")
        .contextValue(GuestHeaders.euros(stay.total()))
        .confirmLabel("Preautorizar — " + GuestHeaders.euros(stay.total()))
        .build());
    return VerticalLayout.builder().style("width: 100%; gap: .5rem;").content(contenido).build();
  }

  /** Modo extras: el catálogo de ancillaries con total en vivo + cierre de la selección. */
  Component panelExtras(Stay stay) {
    var seleccionados = seleccionados(stay);
    var contenido = new ArrayList<Component>();
    contenido.add(Text.builder().text("Ancillaries").container(TextContainer.h3).style("margin: 0;").build());
    contenido.add(AddOnPicker.builder()
        .actionId("extras360")
        .currency("€")
        .totalLabel("Total extras")
        .items(r.addOnCatalog.findAll().stream()
            .map(item -> AddOn.builder()
                .id(item.id())
                .icon(item.icon())
                .title(item.title())
                .description(item.description())
                .price(item.price() == null ? null : item.price().doubleValue())
                .unit(item.unit())
                .includedLabel(item.includedLabel())
                .added(seleccionados.contains(item.id()))
                .build())
            .toList())
        .build());
    contenido.add(Button.builder().label("Cerrar selección").actionId("cerrarExtras")
        .buttonStyle(ButtonStyle.primary).build());
    return VerticalLayout.builder().style("width: 100%; gap: .75rem;").content(contenido).build();
  }

  // ── drawers de operación: las tareas que abren formulario van en un Drawer (el id
  // lógico estable permite que el server REFRESQUE el drawer abierto devolviendo otro
  // con el mismo id — p.ej. al cambiar el método de cobro). Los drawers se COMPONEN de
  // campos y botones (la gramática que el renderer VB pinta en su panel: FormFields →
  // formulario, Buttons → fila de acciones) ─────────────────────────────────────────

  Drawer drawerHabitacion(Stay stay) {
    // el picker COMPLETO (grid de habitaciones con housekeeping + oferta de upgrade) —
    // el drawer VB pinta bloques display, no solo campos y botones
    return Drawer.builder()
        .id("drawer-habitacion")
        .headerTitle("Elegir habitación")
        .width("52rem")
        .content(elegirHabitacion(stay, false))
        .initialData(Map.of("stayId", r.stayId))
        .build();
  }

  Drawer drawerCobro(Stay stay) {
    var guest = r.view().guest();
    var botones = new ArrayList<Component>();
    record Metodo(String id, String label) {}
    for (var metodo : List.of(new Metodo("card", "Tarjeta"), new Metodo("cash", "Efectivo"),
        new Metodo("points", "Puntos (" + GuestHeaders.points(guest) + ")"))) {
      var activo = metodo.id().equals(r.metodoPago);
      botones.add(Button.builder()
          .label((activo ? "● " : "") + metodo.label())
          .actionId("metodoCobro")
          .parameters(Map.of("_method", metodo.id()))
          .build());
    }
    botones.add(Button.builder()
        .label("Preautorizar — " + GuestHeaders.euros(stay.total()))
        .actionId("confirmarCobro")
        .parameters(Map.of("_method", r.metodoPago))
        .buttonStyle(ButtonStyle.primary)
        .build());
    return Drawer.builder()
        .id("drawer-cobro")
        .headerTitle("Cobro / preautorización — " + GuestHeaders.euros(stay.total()))
        .width("28rem")
        .content(VerticalLayout.builder().content(botones).build())
        .initialData(Map.of("stayId", r.stayId, "metodoPago", r.metodoPago))
        .build();
  }

  Drawer drawerExtras(Stay stay) {
    var seleccionados = seleccionados(stay);
    var contenido = new ArrayList<Component>();
    var initialData = new HashMap<String, Object>();
    initialData.put("stayId", r.stayId);
    for (var item : r.addOnCatalog.findAll()) {
      var incluido = item.includedLabel() != null && !item.includedLabel().isBlank();
      if (incluido) {
        continue; // los incluidos en el régimen no se seleccionan
      }
      var campo = "addon_" + item.id();
      initialData.put(campo, seleccionados.contains(item.id()));
      contenido.add(FormField.builder()
          .id(campo)
          .label(item.icon() + " " + item.title() + (item.price() != null ? " — € " + item.price() : ""))
          .dataType(FieldDataType.bool)
          .build());
    }
    contenido.add(Button.builder().label("Guardar extras").actionId("guardarExtras")
        .buttonStyle(ButtonStyle.primary).build());
    return Drawer.builder()
        .id("drawer-extras")
        .headerTitle("Ancillaries")
        .width("30rem")
        .content(VerticalLayout.builder().content(contenido).build())
        .initialData(initialData)
        .build();
  }

  static java.util.Set<String> seleccionados(Stay stay) {
    return stay.addOns().stream().map(SelectedAddOn::addOnId).collect(Collectors.toSet());
  }
}
