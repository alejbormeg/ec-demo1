package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.core.infra.declarative.orchestrators.wizard.WizardStep;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.fluent.Component;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.Setter;

/**
 * Cobro — the stay's guarantee: its total (with the extras chosen) pre-authorized on the guest's card.
 * Shown when the payment or guarantee is pending; cash, a payment link or a transfer are the till's.
 */
@Getter
@Setter
@FormLayout(columns = 1)
public class CobroStep implements WizardStep {

  @Hidden String stayId;
  @Hidden Double totalEstancia;

  /** pendiente → solicitando (TPV) → preautorizado. */
  @Hidden String preauthEstado = "pendiente";

  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  // The stay total framed in a full-width notice: title on top, the amount BIG below. Warning-tinted
  // while a pre-authorization is still needed; the CTA turns into a "solicitando…" line on click and,
  // when the TPV answers (5 s later via SSE), the whole card goes green.
  @Label("")
  Callable<Component> totalBand =
      () -> {
        var ok = "preautorizado".equals(preauthEstado);
        // slotted content doesn't take the notice's themed ink — color it explicitly
        var ink = ok ? "#22703a" : "#925a13";
        var notice = Notice.builder()
            .theme(ok ? "success" : "warning")
            .noIcon(true)
            .text("TOTAL ESTANCIA")
            .fullWidth(true)
            .content(List.of(Text.builder()
                .text(GuestHeaders.euros(totalEstancia))
                .noMargins(true)
                .style("font-size: 1.8rem; font-weight: 700; line-height: 1.1; color: " + ink + ";")
                .build()));
        switch (preauthEstado) {
          case "solicitando" -> notice.status("Solicitando preautorización…");
          case "preautorizado" -> notice.status("✓ Preautorizado");
          default -> notice.actionLabel("Solicitar Preautorización").actionId("requestPreauth");
        }
        return notice.build();
      };

  /** Back to the reservation's page — in the wizard's buttons, with Atrás and Siguiente. */
  @io.mateu.uidl.annotations.Toolbar(buttonStyle = io.mateu.uidl.data.ButtonStyle.tertiary)
  @io.mateu.uidl.annotations.Label("Volver a la reserva")
  public Object volverReserva() {
    return java.net.URI.create("/reservas/" + stayId);
  }
}
