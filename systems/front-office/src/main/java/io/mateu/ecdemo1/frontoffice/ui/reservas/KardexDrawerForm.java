package io.mateu.ecdemo1.frontoffice.ui.reservas;

import io.mateu.ecdemo1.frontoffice.ui.checkin.DocumentoView;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.ButtonStyle;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.fluent.UserTrigger;
import io.mateu.uidl.interfaces.ButtonsSupplier;
import io.mateu.uidl.interfaces.TitleSupplier;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * The kárdex of a pax in the reservation's drawer («Editar» / «A mano»): the check-in's own editor — the
 * same fields in the desk's order, four columns, required as the registration rules say — as a form of
 * its own (embedded: its state and its action are its own, not the page's), saved as the check-in saves
 * it, and recognising the pax from what the guest said. Saved, it closes the drawer with the event the
 * page refreshes its guests on.
 */
@Getter
@Setter
@Service
@Scope("prototype")
public class KardexDrawerForm extends DocumentoView.DocumentoEditor implements TitleSupplier, ButtonsSupplier {

  @Hidden String stayId;
  @Hidden int pax;
  @Hidden String titulo;

  /** The form of this pax's kárdex, filled as the check-in's editor fills it. */
  static KardexDrawerForm of(String stayId, int pax, String titulo) {
    var editor = DocumentoView.forPax(stayId, pax);
    var form = new KardexDrawerForm();
    BeanUtils.copyProperties(editor, form);
    form.stayId = stayId;
    form.pax = pax;
    form.titulo = titulo;
    return form;
  }

  @Override
  public String title() {
    return titulo;
  }

  @Override
  public Collection<UserTrigger> buttons() {
    return List.of(Button.builder().label("Guardar kárdex").actionId("guardarKardex")
        .buttonStyle(ButtonStyle.primary).build());
  }

  @Action
  public Object guardarKardex() {
    var aviso = io.mateu.uidl.di.MateuBeanProvider.getBean(DocumentoView.class).saveKardex(stayId, pax, this);
    var nombre = DocumentoView.nombreCompleto(getNombre(), getApellidos());
    return List.of(new Message(aviso != null ? aviso : "Kárdex guardado — " + (nombre == null ? "huésped " + pax : nombre)),
        UICommand.closeModal("cardex-guardado"));
  }
}
