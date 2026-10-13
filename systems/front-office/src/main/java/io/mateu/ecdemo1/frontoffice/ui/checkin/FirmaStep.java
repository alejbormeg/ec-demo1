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

/**
 * Firma — the welcome signature on the lobby tablet (Civitfun): the registration document sent to it,
 * then signed. Shown when the signature is pending.
 */
@Getter
@Setter
@FormLayout(columns = 1)
public class FirmaStep implements WizardStep {

  @Hidden String stayId;

  /** pendiente → enviada (tablet) → firmada. */
  @Hidden String firmaEstado = "pendiente";

  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  // the send button → an "Enviado · Esperando firma" chip while the tablet waits (the wizard's SSE
  // flux flips the state 5 s later) → a green notice once signed
  @Label("")
  Callable<Component> firma =
      () -> {
        if ("firmada".equals(firmaEstado)) {
          return Notice.builder()
              .theme("success")
              .text("Firma capturada — documento de registro firmado en la tablet Civitfun")
              .fullWidth(true)
              .build();
        }
        var enviado = "enviada".equals(firmaEstado);
        return StatusList.builder()
            .style("width: 100%;")
            .items(List.of(StatusItem.builder()
                .id("firma")
                .icon("✍")
                .title("Firma de bienvenida — tablet Civitfun")
                .description("Envía el documento de registro a la tablet del mostrador para la firma del huésped")
                .status(enviado ? "Enviado · Esperando firma" : null)
                .statusColor(enviado ? "warning" : null)
                .actionLabel(enviado ? null : "Enviar a tablet")
                .actionId(enviado ? null : "sendToTablet")
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
