package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.core.infra.declarative.orchestrators.wizard.WizardStep;
import io.mateu.ecdemo1.frontoffice.domain.room.HousekeepingStatus;
import io.mateu.ecdemo1.frontoffice.domain.room.Room;
import io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Badge;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.OfferCard;
import io.mateu.uidl.data.ResourceGrid;
import io.mateu.uidl.data.ResourceItem;
import io.mateu.uidl.fluent.Component;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.Setter;

/** Step 2 — Habitación: room assignment grid plus the current-room vs upgrade offer cards. */
@Getter
@Setter
@FormLayout(columns = 1)
public class HabitacionStep implements WizardStep {

  @Hidden String stayId;

  @Hidden String habitacionSeleccionada;

  @Hidden boolean upgradeAnadido;

  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  @Label("")
  Callable<Component> preferenciasCliente =
      () ->
          HorizontalLayout.builder()
              .spacing(true)
              .wrap(true)
              .style("margin-top: 0.75rem; margin-bottom: 0.75rem;")
              .content(
                  FrontOffice.stayView(stayId).guest().preferences().stream()
                      .map(
                          pref -> (Component) Badge.builder().text(pref.text()).pill(true).build())
                      .toList())
              .build();

  @Label("")
  Callable<Component> habitaciones =
      () -> {
        var stay = FrontOffice.stayView(stayId).stay();
        // The PMS's rooms of the stay's type, as Opera has them now (the check-in assigns the chosen one there).
        var floor = FrontOffice.roomsFor(stay, 12);
        return ResourceGrid.builder()
            .style("width: 100%;")
            .actionId("pickRoom")
            .columns(4)
            .recommendedLabel("RECOMENDADA")
            .items(
                floor.stream()
                    .map(room -> item(room, stay.roomNumber(), habitacionSeleccionada))
                    .toList())
            .build();
      };

  /**
   * A room of the grid. Ready (vacant, and as Opera's housekeeping must say for the property to assign
   * it — XMAR: inspected) in green; a vacant one not ready yet greyed, with why, and still selectable:
   * the desk may give it on purpose, and Opera may then refuse it; an occupied or out of order one
   * cannot be chosen.
   */
  public static ResourceItem item(Room room, String reservedRoom, String selectedRoom) {
    var occupied = !room.assignable();
    var ready = FrontOffice.ready(room);
    return ResourceItem.builder()
        .id(room.number())
        .title(room.number())
        .subtitle(room.type() != null ? room.type() : occupied ? "Ocupada" : "Libre")
        .statusLabel(occupied ? "Ocupada" : ready ? "Lista · " + housekeepingLabel(room.housekeeping())
            : housekeepingLabel(room.housekeeping()))
        .statusColor(ready ? "success" : "contrast")
        .note(ready ? null : room.maintenanceNote() != null ? "No lista — " + room.maintenanceNote()
            : occupied ? null : "No lista")
        .noteColor(ready ? null : occupied ? "error" : "warning")
        .disabled(occupied)
        .recommended(room.number().equals(reservedRoom))
        .selected(room.number().equals(selectedRoom))
        .build();
  }

  public static String housekeepingLabel(HousekeepingStatus status) {
    return switch (status) {
      case DIRTY -> "Sucia";
      case CLEAN -> "Limpia";
      case INSPECTED -> "Inspeccionada";
    };
  }

  @Label("")
  Callable<Component> ofertaUpgrade =
      () -> {
        var stay = FrontOffice.stayView(stayId).stay();
        var selected =
            habitacionSeleccionada != null ? habitacionSeleccionada : stay.roomNumber();
        var floor =
            // a stay the integration wrote has no room yet: nothing picked, nothing to name
            selected == null || selected.isBlank() ? null
                : FrontOffice.room(selected).map(Room::floor).map(String::valueOf)
                    .orElseGet(() -> selected.length() >= 2 ? selected.substring(0, 2) : selected);
        return HorizontalLayout.builder()
            .spacing(true)
            .wrap(true)
            .style("margin-top: 1rem;")
            .content(
                List.of(
                    OfferCard.builder()
                        .id("asignada")
                        .style("flex: 1 1 340px; min-width: 320px;")
                        .tag("HABITACIÓN ASIGNADA")
                        .title(stay.roomType())
                        .subtitle(floor == null ? "Sin habitación asignada — elige una arriba"
                            : "Hab. " + selected + " · Planta " + floor)
                        .features(List.of("42 m²", "Vista mar lateral", "Cama King", "Balcón"))
                        .current(true)
                        .currentLabel("✓ Incluida en tu reserva")
                        .build(),
                    OfferCard.builder()
                        .id("upgrade")
                        .style("flex: 1 1 340px; min-width: 320px;")
                        .tag("UPGRADE DISPONIBLE")
                        .title("Master Oceanfront Suite")
                        .subtitle("Planta 14 · Primera línea")
                        .features(
                            List.of(
                                "68 m²", "Vista mar frontal", "Terraza + jacuzzi", "Sofá lounge"))
                        .priceLabel("+ € 65 / noche")
                        .actionLabel("Mejorar a esta habitación")
                        .actionId("upgrade")
                        .added(upgradeAnadido)
                        .addedLabel("✓ Upgrade añadido")
                        .build()))
            .build();
      };

  /** Back to the reservation's page — in the wizard's buttons, with Atrás and Siguiente. */
  @io.mateu.uidl.annotations.Toolbar(buttonStyle = io.mateu.uidl.data.ButtonStyle.tertiary)
  @io.mateu.uidl.annotations.Label("Volver a la reserva")
  public Object volverReserva() {
    return java.net.URI.create("/reservas/" + stayId);
  }
}
