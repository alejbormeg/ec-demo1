package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.core.infra.declarative.orchestrators.wizard.WizardStep;
import io.mateu.ecdemo1.frontoffice.ui.common.FrontOffice;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.ecdemo1.frontoffice.ui.common.NoticeItems;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.Setter;

/**
 * Step 0 — Avisos: the reception notices of the stay's guests for the check-in, as Salesforce keeps
 * them (through the MDM). A blocking one must be read: «He leído el aviso», which the check-in then
 * records — and without which it is refused, whoever asks.
 */
@Getter
@Setter
@FormLayout(columns = 1)
public class AvisosStep implements WizardStep, VisibilitySupplier {

  @Hidden String stayId;

  /** What the desk is shown — the blocking notices' fingerprint when the step was drawn. */
  @Hidden String fingerprint;

  @Label("")
  Callable<Component> header = () -> GuestHeaders.checkInHeader(stayId);

  @Label("")
  Callable<Component> avisos = () -> {
    var notices = FrontOffice.checkInNotices(stayId);
    var blocking = notices.stream().anyMatch(p -> p.notice().blocking());
    var content = new ArrayList<Component>();
    content.add(Notice.builder()
        .theme(blocking ? "danger" : "warning")
        .text(blocking
            ? "Hay un aviso BLOQUEANTE: léelo y marca «He leído el aviso» para poder confirmar el check-in"
            : "Avisos de recepción de la estancia: de los huéspedes, de la reserva y de su agencia")
        .fullWidth(true)
        .build());
    content.add(StatusList.builder().items(NoticeItems.items(notices)).compact(true).style("width: 100%;").build());
    return VerticalLayout.builder().style("width: 100%; gap: .75rem;").content(content).build();
  };

  @Label("He leído el aviso")
  boolean leido;

  /** The checkbox only when there is something blocking to read, and not read yet. */
  @Override
  public boolean isHidden(String memberName, HttpRequest httpRequest) {
    if ("leido".equals(memberName)) {
      return FrontOffice.checkInNotices(stayId).stream().noneMatch(p -> p.notice().blocking())
          || FrontOffice.checkInNoticesRead(stayId);
    }
    return false;
  }
}
