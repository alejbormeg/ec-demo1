package io.mateu.ecdemo1.frontoffice.ui.reservas;

import io.mateu.ecdemo1.frontoffice.ui.checkin.DocumentoView;

import io.mateu.ecdemo1.frontoffice.domain.guest.Guest;
import io.mateu.ecdemo1.frontoffice.domain.guest.KardexChange;
import io.mateu.ecdemo1.frontoffice.domain.stay.IncidentStatus;
import io.mateu.ecdemo1.frontoffice.domain.stay.Stay;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FormField;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.StatusItem;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.TextContainer;
import io.mateu.uidl.data.TextSize;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * The people of the Reserva 360: the guests' rail with each pax's kárdex and its actions, the
 * holder's profile, the kárdex form of the drawer, and the plain lists a stay in the house or gone
 * shows of them.
 */
final class HuespedesPanel {

  private final ReservaOverview r;

  HuespedesPanel(ReservaOverview r) {
    this.r = r;
  }

  /**
   * El carril de huéspedes: cada pax con su estado documental y sus acciones. {@code withHeading}
   * pinta el h3 "Huéspedes en la habitación" — fuera cuando el contenedor ya titula (el panel
   * overview del foldout se llama "Huéspedes").
   */
  Component rail(Stay stay, boolean withHeading) {
    var guest = r.view().guest();
    var ops = r.queries.ops(stay.id());
    var items = new ArrayList<StatusItem>();
    // El titular lleva, campo a campo, lo que recepción cambió y el maestro de clientes
    // (Salesforce) aún no ha aprobado — o ha rechazado. Aprobado, el dato se queda sin marca.
    var kardex = r.kardex.changeOf(guest.id()).filter(KardexChange::marked).orElse(null);
    // los avisos de recepción de cada pax para el check-in (Salesforce, vía el MDM), bajo su fila
    var avisos = r.notices.forStay(stay, io.mateu.ecdemo1.frontoffice.domain.notice.Notice.Moment.PRE_ARRIVAL,
        io.mateu.ecdemo1.frontoffice.domain.notice.Notice.Moment.CHECK_IN);
    items.add(conAvisos(conKardex(paxItem(1, io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.withFlag(stay.id(), 1, guest.id(), guest.name()), docAdulto(guest.document()),
        guest.identityComplete(), ops.isNoShow(1)), ops.isNoShow(1) ? null : kardex, guest),
        conCliente(stay, 1, io.mateu.ecdemo1.frontoffice.ui.common.NoticeItems.lines(avisos, 1))));
    var companions = stay.companions();
    for (int i = 0; i < companions.size(); i++) {
      var companion = companions.get(i);
      items.add(conAvisos(paxItem(i + 2, io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.withFlag(stay.id(), i + 2, companion.companionId(), companion.name()), companion.description(),
          companion.identityComplete(), ops.isNoShow(i + 2)),
          conCliente(stay, i + 2, io.mateu.ecdemo1.frontoffice.ui.common.NoticeItems.lines(avisos, i + 2))));
    }
    for (int i = 2 + companions.size(); i <= stay.pax(); i++) {
      items.add(paxItem(i, "Acompañante " + i, "Pendiente de registro", false, ops.isNoShow(i)));
    }
    var contenido = new ArrayList<Component>();
    if (withHeading) {
      contenido.add(Text.builder().text("Huéspedes en la habitación")
          .container(TextContainer.h3).style("margin: 0;").build());
    }
    contenido.add(StatusList.builder().items(items).compact(true).frameless(true)
        .style("width: 100%;").build());
    return VerticalLayout.builder().style("width: 100%; gap: .5rem;").content(contenido).build();
  }

  /** Las líneas del pax con, delante, quién es en la cadena: cliente conocido (y su resumen) o posible. */
  static List<String> conCliente(Stay stay, int pax, List<String> lines) {
    var linea = io.mateu.ecdemo1.frontoffice.ui.common.CustomerPanels.railLine(
        io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.recognition(stay.id(), pax));
    if (linea.isEmpty()) {
      return lines;
    }
    var all = new ArrayList<String>();
    all.add(linea.get());
    all.addAll(lines);
    return all;
  }

  /**
   * «Cliente»: quién es en la cadena el pax elegido (uno por pax con más de uno) — «Cliente conocido»
   * con el resumen de sus estancias y su Riu Class, o «Posible cliente conocido» con nombres y fechas
   * de nacimiento. Lo que diga el huésped se escribe en su kárdex («Editar»): al guardarlo, su número Riu
   * Class o su email dan certeza, y su nombre y fecha de nacimiento, solo «posible».
   */
  Component cliente(Stay stay) {
    var pax = Math.max(1, Math.min(r.paxCliente, stay.pax()));
    var contenido = new ArrayList<Component>();
    contenido.add(Text.builder().text("Cliente").container(TextContainer.h3).style("margin: 0;").build());
    if (stay.pax() > 1) {
      var botones = new ArrayList<Component>();
      for (int i = 1; i <= stay.pax(); i++) {
        botones.add(Button.builder()
            .id("cliente-pax-" + i)
            .label(i + "/" + stay.pax())
            .actionId("clientePax")
            .parameters(java.util.Map.of("paxIndex", i))
            .buttonStyle(i == pax ? io.mateu.uidl.data.ButtonStyle.primary : null)
            .build());
      }
      contenido.add(io.mateu.uidl.data.HorizontalLayout.builder().content(botones)
          .style("gap: .5rem; flex-wrap: wrap;").build());
    }
    contenido.add(io.mateu.ecdemo1.frontoffice.ui.common.CustomerPanels.panel(
        io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.recognition(stay.id(), pax)));
    return VerticalLayout.builder().style("width: 100%; gap: .5rem;").content(contenido).build();
  }


  /** Preferencias, última estancia, quejas pendientes e historial del huésped principal. */
  Component perfilCliente() {
    var guest = r.view().guest();
    // el titular conocido en la cadena, con estancias: el historial de clientes, no los datos de demo
    var historial = io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.recognition(r.stayId, 1).stays();
    var contenido = new ArrayList<Component>();
    contenido.add(Text.builder().text("Preferencias").container(TextContainer.h4).style("margin: 0;").build());
    contenido.add(io.mateu.uidl.data.BulletedList.builder()
        .items(guest.preferences().stream().map(p -> p.text()).toList())
        .build());
    contenido.add(Text.builder().text("Última estancia")
        .container(TextContainer.h4).style("margin: 1.5rem 0 0;").build());
    if (historial.isPresent()) {
      var s = historial.get();
      contenido.add(Text.builder().text(io.mateu.ecdemo1.frontoffice.ui.common.CustomerPanels.lastStay(s).orElse("—"))
          .noMargins(true).build());
      contenido.add(Text.builder().text(s.hotels() + (s.hotels() == 1 ? " hotel" : " hoteles")
          + (s.topHotel() == null ? "" : " · el más repetido: " + s.topHotel())).size(TextSize.xs).noMargins(true).build());
    } else {
      contenido.add(Text.builder().text(guest.lastStaySummary()).noMargins(true).build());
      contenido.add(Text.builder().text(guest.lastStayComplementaryInfo()).size(TextSize.xs).noMargins(true).build());
    }
    if (guest.complaints() > 0) {
      contenido.add(Notice.builder()
          .text(guest.complaints() + " quejas pendientes")
          .theme("danger").slim(true).fullWidth(true)
          .build());
    }
    contenido.add(Text.builder()
        .text(historial.map(s -> io.mateu.ecdemo1.frontoffice.ui.common.CustomerPanels.stays(s)
                + io.mateu.ecdemo1.frontoffice.ui.common.CustomerPanels.since(s, null))
            .orElse(guest.stays() + " estancias · Cliente desde "
                + (java.time.LocalDate.now().getYear() - guest.yearsAsClient() - 1)))
        .size(TextSize.xs).noMargins(true).build());
    return VerticalLayout.builder().style("width: 100%; gap: .25rem;").content(contenido).build();
  }

  /** Huésped que YA salió: lista SIMPLE de huéspedes (nombre + info, sin las acciones ni
   *  los badges de check-in) y si hubo incidencias durante la estancia. */
  Component infoSalida(Stay stay) {
    var guest = r.view().guest();
    var contenido = new ArrayList<Component>();
    contenido.add(Text.builder().text("Huéspedes").container(TextContainer.h3).style("margin: 0;").build());
    contenido.add(Text.builder().text(io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.withFlag(stay.id(), 1, guest.id(), guest.name())).noMargins(true).build());
    contenido.add(Text.builder().text(docAdulto(guest.document())).size(TextSize.xs).noMargins(true).build());
    for (int i = 0; i < stay.companions().size(); i++) {
      var companion = stay.companions().get(i);
      contenido.add(Text.builder().text(io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.withFlag(stay.id(), i + 2, companion.companionId(), companion.name()))
          .noMargins(true).build());
      contenido.add(Text.builder().text(companion.description()).size(TextSize.xs).noMargins(true).build());
    }
    contenido.add(Text.builder().text("Incidencias (" + stay.incidents().size() + ")")
        .container(TextContainer.h3).style("margin: 1.5rem 0 0;").build());
    if (stay.incidents().isEmpty()) {
      contenido.add(Notice.builder()
          .theme("success")
          .text("Sin incidencias durante la estancia")
          .slim(true).fullWidth(true)
          .build());
    } else {
      contenido.add(StatusList.builder()
          .compact(true).frameless(true)
          .itemHeadingLevel(4)
          .style("width: 100%;")
          .items(stay.incidents().stream()
              .map(i -> StatusItem.builder()
                  .id("inc-" + i.code())
                  .title(i.title())
                  .description(i.type() != null ? i.type().label() : null)
                  .status(i.status() == IncidentStatus.RESOLVED ? "✓ Resuelta" : "Sin resolver")
                  .statusColor(i.status() == IncidentStatus.RESOLVED ? "success" : "error")
                  .build())
              .toList())
          .build());
    }
    return VerticalLayout.builder().style("width: 100%; gap: .25rem;").content(contenido).build();
  }

  /** Info secundaria del general overview en casa: los huéspedes (solo datos) y la salida. */
  List<Component> infoSecundaria(Stay stay) {
    var view = r.view();
    var contenido = new ArrayList<Component>();
    contenido.add(Text.builder().text("Información").container(TextContainer.h2).style("margin: 0;").build());
    contenido.add(Text.builder().text("Huéspedes").container(TextContainer.h3).style("margin: 0;").build());
    contenido.add(Text.builder().text(io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.withFlag(stay.id(), 1, view.guest().id(), view.guest().name()))
        .noMargins(true).build());
    contenido.add(Text.builder().text(docAdulto(view.guest().document())).size(TextSize.xs).noMargins(true).build());
    for (int i = 0; i < stay.companions().size(); i++) {
      var companion = stay.companions().get(i);
      contenido.add(Text.builder().text(io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice.withFlag(stay.id(), i + 2, companion.companionId(), companion.name()))
          .noMargins(true).build());
      // la descripción del acompañante ya incluye su documento
      contenido.add(Text.builder().text(companion.description()).size(TextSize.xs).noMargins(true).build());
    }
    // quién es cada huésped en la cadena (en casa también se puede confirmar)
    contenido.add(cliente(stay));
    contenido.add(Text.builder().text("Salida").container(TextContainer.h3).style("margin: 0;").build());
    contenido.add(Text.builder().text(EstanciaPanel.salida(stay, view.folio())).noMargins(true).build());
    contenido.add(Text.builder().text(stay.nights() + " noches · " + stay.board())
        .size(TextSize.xs).noMargins(true).build());
    return contenido;
  }

  /**
   * El formulario del kárdex del huésped seleccionado — el mismo que el del check-in, en el orden en que
   * recepción lo pide — contenido del DRAWER que abre "Editar"/"A mano"; el título viaja en el header del
   * drawer. Los campos se llaman {@code k_} + la propiedad del editor del check-in: guardarPax rehace el
   * editor con ellos. Los que exigen las normas de registro van como obligatorios; país de expedición y
   * adulto responsable, solo si alguna los pide.
   */
  Component formularioPax(DocumentoView.DocumentoEditor editor) {
    var campos = new ArrayList<Component>();
    // el titular: encima de los campos, cada campo que recepción cambió y cómo lo tiene
    // Salesforce (pendiente, o rechazado con su motivo)
    if (r.paxSeleccionado <= 1) {
      var guest = r.view().guest();
      r.kardex.changeOf(guest.id())
          .filter(KardexChange::marked)
          .ifPresent(kardex -> campos.add(StatusList.builder()
              .items(camposEnSalesforce(kardex, guest)).compact(true).frameless(true)
              .style("width: 100%;").build()));
    }
    if (editor.getBaseLegal() != null && !editor.getBaseLegal().isBlank()) {
      campos.add(io.mateu.uidl.data.Notice.builder().theme("info").text(editor.getBaseLegal()).fullWidth(true).build());
    }
    var formulario = new ArrayList<Component>();
    for (var c : CAMPOS_KARDEX) {
      if (editor.isHidden(c[0], null)) {
        continue;
      }
      formulario.add(FormField.builder()
          .id(PREFIJO_KARDEX + c[0])
          .label(c[1])
          .dataType(FieldDataType.valueOf(c[2]))
          .required(editor.isRequired(c[0], null))
          .readOnly("acompanante".equals(c[0]) || "cardexProvisional".equals(c[0]))
          .style("width: 100%;")
          .build());
    }
    campos.add(io.mateu.uidl.data.FormLayout.builder().maxColumns(2).content(formulario).style("width: 100%;").build());
    campos.add(Button.builder().label("Guardar kárdex").actionId("guardarPax")
        .buttonStyle(io.mateu.uidl.data.ButtonStyle.primary).build());
    return VerticalLayout.builder().content(campos).style("width: 100%; gap: .5rem;").build();
  }

  /** Prefijo de los campos del kárdex en el estado del drawer. */
  static final String PREFIJO_KARDEX = "k_";

  /** Propiedad del editor del check-in, etiqueta y tipo de cada campo del kárdex, en el orden de recepción. */
  static final String[][] CAMPOS_KARDEX = {
      {"apellidos", "Apellidos", "string"}, {"nombre", "Nombre", "string"}, {"email", "Email", "string"},
      {"riuClass", "Nº Riu Class", "string"}, {"fechaNacimiento", "Fecha de nacimiento", "date"},
      {"sexo", "Sexo", "string"}, {"documento", "Documento", "string"},
      {"tipoDocumento", "Tipo de documento", "string"}, {"caducidad", "Caducidad", "date"},
      {"fechaExpedicion", "Expedición", "date"}, {"paisExpedicion", "País de expedición", "string"},
      {"nacionalidad", "Nacionalidad", "string"}, {"idioma", "Idioma", "string"},
      {"direccion", "Dirección", "string"}, {"ciudad", "Población", "string"},
      {"codigoPostal", "Código postal", "string"}, {"provincia", "Provincia", "string"},
      {"lugarNacimiento", "Ciudad de nacimiento", "string"}, {"paisResidencia", "País de residencia", "string"},
      {"telefono", "Teléfono", "string"}, {"fax", "Fax", "string"},
      {"tutor", "Adulto responsable y parentesco", "string"},
      {"acompanante", "Es acompañante", "bool"}, {"cardexProvisional", "Es cárdex provisional", "bool"},
      {"aceptaPublicidad", "Acepta publicidad", "bool"},
  };

  /** Un item por campo cambiado: el dato, y su estado en Salesforce. */
  static List<StatusItem> camposEnSalesforce(KardexChange kardex, Guest guest) {
    return kardex.shownFor(guest).stream()
        .map(f -> StatusItem.builder()
            .id(f.field())
            .title(f.label())
            .description(kardex.pending()
                ? f.after()
                : "Propuesto " + f.after() + " — se queda " + guest.valueOf(f.field()))
            .status(kardex.pending() ? "Pendiente de Salesforce" : "Rechazado")
            .statusColor(kardex.pending() ? "warning" : "error")
            .lines(kardex.pending() || kardex.reason() == null ? List.of() : List.of("Motivo: " + kardex.reason()))
            .build())
        .toList();
  }


  static StatusItem paxItem(int pax, String title, String description, boolean complete, boolean noShow) {
    // también con la identidad verificada: el recepcionista puede querer volver a escanear
    // el documento o validar/corregir algún dato del cardex. Un pax NO SHOW pierde las
    // acciones de registro y queda solo con la de revertir la marca.
    return StatusItem.builder()
        .id(String.valueOf(pax))
        .avatar(initials(title))
        .title(title)
        .description(noShow ? "No se ha presentado" : description)
        .status(noShow ? "No show" : complete ? "Kárdex OK" : "Sin kárdex")
        .statusColor(noShow ? "error" : complete ? "success" : "warning")
        .actionLabel(noShow ? null : (complete ? "Reescanear" : "Escanear"))
        .actionId(noShow ? null : "escanearPax")
        .actionIcon(noShow ? null : "vaadin:barcode")
        .actionLabel2(noShow ? null : (complete ? "Editar" : "A mano"))
        .actionId2(noShow ? null : "rellenarPax")
        .actionIcon2(noShow ? null : "vaadin:pencil")
        .actionLabel3(noShow ? "Revertir" : "No show")
        .actionId3("noShowPax")
        .actionIcon3(noShow ? "vaadin:rotate-left" : "vaadin:ban")
        .build();
  }

  /**
   * El pax con el estado de su kárdex frente a Salesforce: la marca del pax dice que hay un cambio
   * pendiente o rechazado, y cada campo cambiado va en su línea.
   */
  static StatusItem conKardex(StatusItem item, KardexChange kardex, Guest guest) {
    if (kardex == null) {
      return item;
    }
    return StatusItem.builder()
        .id(item.id()).icon(item.icon()).avatar(item.avatar()).title(item.title())
        .description(item.description())
        .actionLabel(item.actionLabel()).actionId(item.actionId()).actionIcon(item.actionIcon())
        .actionLabel2(item.actionLabel2()).actionId2(item.actionId2()).actionIcon2(item.actionIcon2())
        .actionLabel3(item.actionLabel3()).actionId3(item.actionId3()).actionIcon3(item.actionIcon3())
        .status(kardex.label())
        .statusColor(kardex.pending() ? "warning" : "error")
        .lines(kardex.lines(guest))
        .build();
  }

  /** El pax con sus avisos de recepción, cada uno en una línea bajo las de su kárdex. */
  static StatusItem conAvisos(StatusItem item, List<String> avisos) {
    if (avisos.isEmpty()) {
      return item;
    }
    var lines = new ArrayList<String>(item.lines() == null ? List.of() : item.lines());
    lines.addAll(avisos);
    return StatusItem.builder()
        .id(item.id()).icon(item.icon()).avatar(item.avatar()).title(item.title())
        .description(item.description())
        .actionLabel(item.actionLabel()).actionId(item.actionId()).actionIcon(item.actionIcon())
        .actionLabel2(item.actionLabel2()).actionId2(item.actionId2()).actionIcon2(item.actionIcon2())
        .actionLabel3(item.actionLabel3()).actionId3(item.actionId3()).actionIcon3(item.actionIcon3())
        .status(item.status())
        .statusColor(item.statusColor())
        .lines(lines)
        .build();
  }

  static String initials(String name) {
    var parts = name.trim().split("\\s+");
    var sb = new StringBuilder();
    for (var part : parts) {
      if (sb.length() < 2 && !part.isBlank()) {
        sb.append(Character.toUpperCase(part.charAt(0)));
      }
    }
    return sb.toString();
  }

  /** El documento del titular, si lo hay: sin él, solo que es adulto (nunca «Doc null»). */
  static String docAdulto(String document) {
    return document == null || document.isBlank() || "null".equals(document)
        ? "Adulto" : "Doc " + document + " · Adulto";
  }
}
