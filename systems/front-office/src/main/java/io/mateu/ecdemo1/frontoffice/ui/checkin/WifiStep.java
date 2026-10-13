package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.core.infra.declarative.orchestrators.wizard.WizardStep;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.StatusItem;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.fluent.Component;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.Setter;

/** Wifi — the guest's access to the hotel's network: their card created. Shown when it is pending. */
@Getter
@Setter
@FormLayout(columns = 1)
public class WifiStep implements WizardStep {

  @Hidden String stayId;
  @Hidden boolean creada;

  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  @Label("")
  Callable<Component> wifi =
      () -> creada
          ? Notice.builder()
              .theme("success")
              .text("Tarjeta wifi creada — red HOTEL_GUEST · clave " + clave(stayId))
              .fullWidth(true)
              .build()
          : StatusList.builder()
              .style("width: 100%;")
              .items(List.of(StatusItem.builder()
                  .id("wifi")
                  .icon("📶")
                  .title("Tarjeta wifi")
                  .description("Crear las credenciales de acceso del huésped a la red HOTEL_GUEST")
                  .actionLabel("Crear")
                  .actionId("crearWifi")
                  .build()))
              .build();

  /** The stay's wifi key, as the reservation's card gives it. */
  static String clave(String stayId) {
    return stayId == null ? "" : "HG-" + Math.abs(stayId.hashCode() % 9000 + 1000);
  }
}
