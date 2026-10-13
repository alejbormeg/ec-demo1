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

/** Llave — the room's key or wristband, encoded at the desk. Shown when it is pending. */
@Getter
@Setter
@FormLayout(columns = 1)
public class LlaveStep implements WizardStep {

  @Hidden String stayId;

  /** pendiente → grabando (encoder) → grabada. */
  @Hidden String llaveEstado = "pendiente";

  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  // encode button → "Grabando…" chip → once encoded the whole card goes green
  @Label("")
  Callable<Component> llave =
      () -> {
        if ("grabada".equals(llaveEstado)) {
          return Notice.builder()
              .theme("success")
              .text("Llave / pulsera grabada — complemento de llave digital")
              .fullWidth(true)
              .build();
        }
        var item = StatusItem.builder()
            .id("llave")
            .icon("🔑")
            .title("Grabar llave / pulsera")
            .description("Complemento de llave digital");
        if ("grabando".equals(llaveEstado)) {
          item.status("Grabando llave…").statusColor("warning");
        } else {
          item.actionLabel("Grabar").actionId("encodeKey");
        }
        return StatusList.builder().style("width: 100%;").items(List.of(item.build())).build();
      };
}
