package io.mateu.ecdemo1.frontoffice.ui.caja;

import io.mateu.ecdemo1.frontoffice.application.Cashier;
import io.mateu.ecdemo1.frontoffice.application.Receipts;
import io.mateu.ecdemo1.frontoffice.application.StayQueries;
import io.mateu.ecdemo1.frontoffice.domain.cashier.Payment;
import io.mateu.ecdemo1.frontoffice.infra.security.DeskUser;
import io.mateu.ecdemo1.frontoffice.ui.common.GuestHeaders;
import io.mateu.ecdemo1.frontoffice.ui.common.OtherSystems;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.ButtonStyle;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.StatusItem;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.fluent.UserTrigger;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.ActionHandler;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import io.mateu.uidl.interfaces.ToolbarSupplier;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * Caja — the cashiering of one stay's account (OPERA's Billing): what it owes in the hotel's currency
 * and its credit; taking a payment or an advance (cash, the card terminal, a payment link by email, a
 * transfer, manual), each with its receipt; the folio's proforma; the credit limit, and cancelling the
 * credit. The use cases are {@link Cashier}'s; the documents, {@link Receipts}'.
 */
@Getter
@Setter
@Title("Caja")
@FormLayout(columns = 1)
// the email only for a payment link, the reference only for a transfer — decided in the browser as the
// desk picks the way to pay
@io.mateu.uidl.annotations.Rule(filter = "true", action = io.mateu.uidl.data.RuleAction.SetDataValue,
    fieldName = "email", fieldAttribute = io.mateu.uidl.data.RuleFieldAttribute.hidden, value = "",
    expression = "state.forma != 'LINK'", actionId = "", result = io.mateu.uidl.data.RuleResult.Continue)
@io.mateu.uidl.annotations.Rule(filter = "true", action = io.mateu.uidl.data.RuleAction.SetDataValue,
    fieldName = "referencia", fieldAttribute = io.mateu.uidl.data.RuleFieldAttribute.hidden, value = "",
    expression = "state.forma != 'TRANSFERENCIA'", actionId = "", result = io.mateu.uidl.data.RuleResult.Continue)
@Service
@Scope("prototype")
public class CajaView implements PostHydrationHandler, ToolbarSupplier, ActionHandler {

  @Getter(AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore final transient Cashier cashier;
  @Getter(AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore final transient Receipts receipts;
  @Getter(AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore final transient StayQueries queries;
  @Getter(AccessLevel.NONE) @com.fasterxml.jackson.annotation.JsonIgnore final transient
      io.mateu.ecdemo1.frontoffice.infra.pms.ChargePostings opera;

  public CajaView(Cashier cashier, Receipts receipts, StayQueries queries,
                  io.mateu.ecdemo1.frontoffice.infra.pms.ChargePostings opera) {
    this.opera = opera;
    this.cashier = cashier;
    this.receipts = receipts;
    this.queries = queries;
  }

  @Hidden String stayId;

  /** What a payment is: of what is owed, or an advance. */
  public enum Tipo {
    @Label("Cobro") COBRO, @Label("Anticipo") ANTICIPO
  }

  /** How the guest pays. */
  public enum Forma {
    @Label("Efectivo") EFECTIVO, @Label("Tarjeta (datáfono)") DATAFONO, @Label("Link de pago por email") LINK,
    @Label("Transferencia") TRANSFERENCIA, @Label("Manual") MANUAL
  }

  @Section(value = "", frameless = true)
  @Label("")
  Callable<Component> cuenta = this::cuenta;

  @io.mateu.uidl.annotations.Tab("Cobrar")
  @Label("Tipo")
  Tipo tipo = Tipo.COBRO;

  @Label("Forma de pago")
  Forma forma = Forma.DATAFONO;

  @Label("Importe")
  BigDecimal importe;

  @Label("Email (link de pago)")
  String email;

  @Label("Referencia")
  String referencia;

  @Label("")
  Button cobrar = new Button("Cobrar", "cobrar");

  @io.mateu.uidl.annotations.Tab("Crédito")
  @Label("")
  Callable<Component> credito = this::credito;


  @Label("Límite de crédito (vacío: el de la preautorización)")
  BigDecimal limite;

  @Label("Motivo (para cancelar el crédito)")
  String motivo;


  // the credit's buttons in one block of the tab: as button fields in a row, Redwood moved them to the
  // form's foot, under every tab
  @Label("")
  Callable<Component> botonesCredito = () -> io.mateu.uidl.data.HorizontalLayout.builder()
      .style("gap: .5rem; flex-wrap: wrap;")
      .content(List.of(
          Button.builder().id("fijar-limite").label("Fijar límite").actionId("fijarLimite").build(),
          Button.builder().id("cancelar-credito").label("Cancelar crédito").actionId("cancelarCredito").build(),
          Button.builder().id("restablecer-credito").label("Restablecer crédito").actionId("restablecerCredito").build()))
      .build();

  @io.mateu.uidl.annotations.Tab("Cobros y recibos")
  @Label("")
  Callable<Component> cobros = this::cobros;

  @io.mateu.uidl.annotations.Tab("Devolver o anular")
  @Label("")
  Callable<Component> devoluciones = this::devoluciones;

  @Override
  public void onHydrated(HttpRequest httpRequest) {
    if (stayId == null || stayId.isBlank()) {
      stayId = GuestHeaders.idFromRoute(httpRequest, "caja");
    }
    if (importe == null && stayId != null && queries.find(stayId).isPresent()) {
      var due = cashier.account(stayId).due();
      importe = due.signum() > 0 ? due : null;
    }
  }

  // ── what the page shows ──────────────────────────────────────────────────────

  Component cuenta() {
    if (stayId == null || queries.find(stayId).isEmpty()) {
      return Text.builder().text("Reserva no encontrada").build();
    }
    var a = cashier.account(stayId);
    var view = queries.view(stayId);
    var c = a.currency();
    var resumen = (view.guest() == null ? "" : view.guest().name() + " · ") + "estancia " + stayId
        + " · Cargos " + Receipts.amount(a.charges(), c) + " · Cobrado " + Receipts.amount(a.paid(), c)
        + (a.deposits().signum() > 0 ? " (anticipos " + Receipts.amount(a.deposits(), c) + ")" : "");
    var saldo = "Saldo pendiente: " + Receipts.amount(a.due(), c);
    return io.mateu.uidl.data.VerticalLayout.builder().style("width: 100%; gap: .5rem;").content(List.of(
        Text.builder().text(saldo).container(io.mateu.uidl.data.TextContainer.h2).style("margin: 0;").build(),
        Text.builder().text(resumen).noMargins(true).build())).build();
  }

  /** Where the stay's credit stands — its limit, over it, or cancelled —, over the fields that change it. */
  Component credito() {
    if (stayId == null || queries.find(stayId).isEmpty()) {
      return Text.builder().text("").build();
    }
    var a = cashier.account(stayId);
    var c = a.currency();
    var credito = a.creditCancelled()
        ? "Crédito cancelado" + (a.creditReason() == null ? "" : " — " + a.creditReason())
            + ": nada se carga a la habitación sin cobrarlo"
        : "Límite de crédito " + Receipts.amount(a.creditLimit(), c) + (a.limitSet() ? "" : " (la preautorización)")
            + (a.overLimit() ? " — superado: pide un cobro o un anticipo" : "");
    return Notice.builder().theme(a.creditCancelled() || a.overLimit() ? "warning" : "info").text(credito)
        .fullWidth(true).build();
  }

  /** One Element per page (Mateu gives them all the same id): the proforma's link and every payment, with its receipt. */
  Component cobros() {
    if (stayId == null || queries.find(stayId).isEmpty()) {
      return Text.builder().text("").build();
    }
    var html = new StringBuilder("<p style=\"margin: 0 0 .5rem;\"><a href=\"")
        .append(OtherSystems.escape(receipts.proformaLink(stayId)))
        .append("\" target=\"_blank\" rel=\"noopener\">Imprimir proforma del folio</a></p>");
    var list = cashier.account(stayId).payments();
    if (list.isEmpty()) {
      html.append("<p style=\"margin: 0;\">Sin cobros todavía</p>");
      return Element.html("div", Map.of("style", "width: 100%;"), html.toString());
    }
    // where each payment is in Opera's folio (registrar-cobro answers on the line PAY:<payment>)
    var enOpera = opera.ofStay(stayId);
    html.append("<table style=\"width: 100%; border-collapse: collapse; font-size: .875rem;\"><tbody>");
    for (var p : list.reversed()) {
      html.append("<tr><td style=\"").append(CELL).append("\">").append(OtherSystems.escape(p.kind().label + " · "
              + p.method().label)).append("</td><td style=\"").append(CELL).append(" text-align: right;\">")
          .append(OtherSystems.escape(Receipts.amount(p.amount(), p.currency()))).append("</td><td style=\"").append(CELL)
          .append("\">").append(OtherSystems.escape(p.status().label + (p.reference() == null ? "" : " · " + p.reference())));
      if (p.status() == Payment.Status.PENDING && p.linkToken() != null) {
        html.append(" · enviado a ").append(OtherSystems.escape(p.email())).append(" · <a href=\"/pagar/")
            .append(OtherSystems.escape(p.linkToken())).append("\" target=\"_blank\" rel=\"noopener\">link de pago</a>");
      }
      if (p.receiptNo() != null) {
        html.append(" · <a href=\"").append(OtherSystems.escape(receipts.receiptLink(p.id())))
            .append("\" target=\"_blank\" rel=\"noopener\">recibo nº ").append(p.receiptNo()).append("</a>");
      }
      var posting = enOpera.get("PAY:" + p.id());
      if (posting != null) {
        html.append("<br><span style=\"opacity: .75;\">").append(OtherSystems.escape(posting.state())).append("</span>");
      }
      html.append("</td></tr>");
    }
    html.append("</tbody></table>");
    return Element.html("div", Map.of("style", "width: 100%;"), html.toString());
  }

  /** What can still be taken back: a captured payment (refunded) or a pending link (cancelled). */
  Component devoluciones() {
    if (stayId == null || queries.find(stayId).isEmpty()) {
      return Text.builder().text("").build();
    }
    var cancelables = cashier.account(stayId).payments().stream()
        .filter(p -> p.status() == Payment.Status.PENDING || p.status() == Payment.Status.CAPTURED).toList();
    if (cancelables.isEmpty()) {
      return Text.builder().text("Nada que devolver ni anular").noMargins(true).build();
    }
    return StatusList.builder().compact(true).style("width: 100%;").items(cancelables.stream().map(p -> StatusItem.builder()
        .id(p.id())
        .title((p.receiptNo() == null ? "Link de pago" : "Recibo nº " + p.receiptNo()) + " · "
            + Receipts.amount(p.amount(), p.currency()))
        .description(p.kind().label + " · " + p.method().label)
        .status(p.status().label)
        .statusColor(p.captured() ? "success" : "warning")
        .actionLabel(p.captured() ? "Devolver" : "Anular link")
        .actionId("anularCobro")
        .build()).toList()).build();
  }

  static final String CELL =
      "text-align: left; padding: .3rem .5rem; border-bottom: 1px solid rgba(128, 128, 128, .25); vertical-align: top;";

  // ── toolbar and actions ──────────────────────────────────────────────────────

  @Override
  public Collection<UserTrigger> toolbar() {
    return List.of(Button.builder().label("Volver a la reserva").actionId("volver").buttonStyle(ButtonStyle.primary).build());
  }

  @Override
  public boolean supportsAction(String actionId) {
    return List.of("volver", "cobrar", "fijarLimite", "cancelarCredito", "restablecerCredito", "anularCobro")
        .contains(actionId);
  }

  @Override
  public Object handleAction(String actionId, HttpRequest httpRequest) {
    if ("volver".equals(actionId)) {
      return URI.create("/reservas/" + stayId);
    }
    try {
      var said = switch (actionId) {
        case "cobrar" -> {
          var p = cashier.take(stayId, tipo == Tipo.ANTICIPO ? Payment.Kind.DEPOSIT : Payment.Kind.PAYMENT, method(forma),
              importe, email, referencia, DeskUser.name());
          importe = null;
          referencia = null;
          yield p.status() == Payment.Status.DECLINED ? "⛔ Tarjeta denegada por el datáfono: " + Receipts.amount(p.amount(),
              p.currency()) + " sin cobrar"
              : p.status() == Payment.Status.PENDING ? "Link de pago de " + Receipts.amount(p.amount(), p.currency())
                  + " enviado a " + p.email() + " (demo: se abre desde «link de pago»)"
              : p.kind().label + " de " + Receipts.amount(p.amount(), p.currency()) + " — recibo nº " + p.receiptNo();
        }
        case "fijarLimite" -> {
          cashier.setLimit(stayId, limite, DeskUser.name());
          yield limite == null ? "Límite de crédito: el de la preautorización" : "Límite de crédito fijado";
        }
        case "cancelarCredito" -> {
          cashier.cancelCredit(stayId, motivo, DeskUser.name());
          yield "Crédito cancelado: la habitación ya no admite cargos sin cobrarlos";
        }
        case "restablecerCredito" -> {
          cashier.restoreCredit(stayId, DeskUser.name());
          yield "Crédito restablecido";
        }
        case "anularCobro" -> {
          var p = cashier.cancel(String.valueOf(httpRequest.runActionRq().parameters().get("_item")), DeskUser.name());
          yield p.receiptNo() == null ? "Link de pago anulado" : "Cobro del recibo nº " + p.receiptNo() + " devuelto";
        }
        default -> null;
      };
      if (importe == null) {
        var due = cashier.account(stayId).due();
        importe = due.signum() > 0 ? due : null;
      }
      return List.of(this, new Message(said));
    } catch (Cashier.Refused e) {
      return List.of(this, new Message("⛔ " + e.getMessage()));
    }
  }

  static Payment.Method method(Forma forma) {
    return switch (forma == null ? Forma.DATAFONO : forma) {
      case EFECTIVO -> Payment.Method.CASH;
      case DATAFONO -> Payment.Method.CARD_PINPAD;
      case LINK -> Payment.Method.PAY_LINK;
      case TRANSFERENCIA -> Payment.Method.TRANSFER;
      case MANUAL -> Payment.Method.MANUAL;
    };
  }
}
