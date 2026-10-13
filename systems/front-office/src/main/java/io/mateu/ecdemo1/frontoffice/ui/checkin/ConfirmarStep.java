package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.core.infra.declarative.orchestrators.wizard.WizardStep;
import io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.StatusItem;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.fluent.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.Setter;

/**
 * The last step — Confirmar: the summary of the check-in (the stay, its total, the guests in the room,
 * where each operation stands) and what confirming sends; the operations themselves are the steps before.
 */
@Getter
@Setter
@FormLayout(columns = 1)
public class ConfirmarStep implements WizardStep, io.mateu.uidl.interfaces.VisibilitySupplier {

  @Hidden String stayId;
  @Hidden Double totalEstancia;

  // No section → full-width band across the top. Frameless: the header card brings its own chrome.
  @Section(value = "", frameless = true)
  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  // What the check-in still lacks (documents, signature): arriving, «Confirmar check-in» does not go
  // through without it — «Forzar check-in», with a reason, does —; in house (a forced check-in being
  // completed), what «Completar» still waits for. Nothing missing, nothing shown.
  @Label("")
  Callable<Component> pendientes = () -> ForcedCheckInViews.pendingInWizard(stayId);

  @Label("Motivo del check-in forzado")
  @io.mateu.uidl.annotations.Help("Obligatorio para forzar: por qué entra sin lo que falta (queda auditado)")
  String motivoForzado;

  @Label("")
  Callable<Component> forzar = () -> io.mateu.uidl.data.Button.builder()
      .id("forzar-checkin")
      .label("Forzar check-in")
      .actionId("forzarCheckin")
      .color(io.mateu.uidl.data.ButtonColor.error)
      .build();

  // The reservation summary as a read-only property list (label left, value right).
  @Section(value = "Resumen", propertyList = true)
  @Label("Huésped principal")
  String huespedPrincipal;

  @Label("Habitación")
  String habitacionAsignada;

  @Label("Estancia")
  String estancia;

  @Label("Régimen")
  String regimen;

  @Label("Total estancia")
  String total;

  @Section("Huéspedes en la habitación")
  @Label("")
  Callable<Component> huespedesEnHabitacion =
      () -> {
        var view = FrontOffice.stayView(stayId);
        var stay = view.stay();
        var guest = view.guest();
        var items = new ArrayList<StatusItem>();
        items.add(
            StatusItem.builder()
                .id("principal")
                .avatar(initials(guest.name()))
                .title(guest.name())
                .description(
                    (guest.document() == null || guest.document().isBlank()
                            ? "Documento escaneado"
                            : "Doc " + guest.document())
                        + " · Adulto")
                .status("✓ Identidad verificada")
                .statusColor("success")
                .build());
        for (var companion : stay.companions()) {
          items.add(
              StatusItem.builder()
                  .id(companion.companionId())
                  .avatar(initials(companion.name()))
                  .title(companion.name())
                  .description(companion.description())
                  .status("✓ Identidad verificada")
                  .statusColor("success")
                  .build());
        }
        for (int i = 2 + stay.companions().size(); i <= stay.pax(); i++) {
          items.add(
              StatusItem.builder()
                  .id("pax" + i)
                  .avatar("A" + i)
                  .title("Acompañante " + i)
                  .description("Pendiente de registro")
                  .status("Pendiente")
                  .statusColor("warning")
                  .build());
        }
        // frameless: the section card already frames the list — dividers only
        return StatusList.builder()
            .items(items)
            .compact(true)
            .frameless(true)
            .style("width: 100%;")
            .build();
      };

  // where each check-in operation stands — done in its step, or at the reservation's cards
  @Section("Operaciones")
  @Label("")
  Callable<Component> operaciones =
      () -> {
        var ops = FrontOffice.ops(stayId);
        var stay = FrontOffice.stayView(stayId).stay();
        record Op(String id, String icon, String title, boolean done) {}
        var items = List.of(
                new Op("habitacion", "🛏️", "Habitación" + (stay.hasRoom() ? " " + stay.roomNumber() : ""), stay.hasRoom()),
                new Op("firma", "✍", "Firma del registro", ops.firma()),
                new Op("extras", "🎁", "Extras", ops.extras()),
                new Op("cobro", "💳", "Cobro / preautorización", ops.cobro()),
                new Op("llave", "🔑", "Llave / pulsera", ops.llave()),
                new Op("wifi", "📶", "Tarjeta wifi", ops.wifi()))
            .stream()
            .map(op -> StatusItem.builder().id(op.id()).icon(op.icon()).title(op.title())
                .status(op.done() ? "✓ Hecha" : "Pendiente").statusColor(op.done() ? "success" : "warning").build())
            .toList();
        return StatusList.builder().items(items).compact(true).frameless(true).style("width: 100%;").build();
      };

  // what confirming sends, side by side
  @Section(value = "Al confirmar", frameless = true)
  @Label("")
  Callable<Component> alConfirmar =
      () -> HorizontalLayout.builder()
          .content(List.of(
              Notice.builder().theme("success").text("Email de bienvenida — envío automatizado al confirmar")
                  .style("flex: 1 1 16rem; width: auto;").build(),
              Notice.builder().theme("success")
                  .text("Parte de viajeros (SES) — se enviará automáticamente al confirmar el check-in")
                  .style("flex: 1 1 16rem; width: auto;").build()))
          .style("width: 100%; gap: 1rem; flex-wrap: wrap;")
          .build();

  /** The reason and «Forzar check-in» only for an arriving stay with something missing. */
  @Override
  public boolean isHidden(String memberName, io.mateu.uidl.interfaces.HttpRequest httpRequest) {
    if ("motivoForzado".equals(memberName) || "forzar".equals(memberName)) {
      return !ForcedCheckInViews.canForce(stayId);
    }
    return false;
  }

  /** Initials for the guest avatar: first letter of the first two words of the name. */
  static String initials(String name) {
    if (name == null || name.isBlank()) {
      return "?";
    }
    var words = name.trim().split("\\s+");
    var initials = new StringBuilder();
    for (int i = 0; i < Math.min(2, words.length); i++) {
      initials.append(Character.toUpperCase(words[i].charAt(0)));
    }
    return initials.toString();
  }
}
