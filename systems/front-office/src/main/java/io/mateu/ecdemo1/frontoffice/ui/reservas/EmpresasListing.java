package io.mateu.ecdemo1.frontoffice.ui.reservas;

import io.mateu.ecdemo1.frontoffice.application.StayCompanies;
import io.mateu.ecdemo1.frontoffice.domain.company.Companies;
import io.mateu.ecdemo1.frontoffice.infra.security.DeskUser;
import io.mateu.ecdemo1.frontoffice.ui.Paging;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Listing;
import io.mateu.uidl.interfaces.Searchable;
import java.util.List;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * The companies of the ERP to bill a stay to, as a listing like a crud's — the search box, the
 * columns, the pages — in the company's drawer: a click on a row sets that company on the stay,
 * closes the drawer and repaints the reservation ({@code empresa-elegida}). The stay travels in the
 * listing's own state, as the cardex drawer's form carries it.
 */
@Service
@Scope("prototype")
public class EmpresasListing implements Listing<EmpresasListing.Empresa>, Searchable {

  static final String ELEGIDA = "empresa-elegida";

  @Hidden String stayId;

  public record Empresa(
      @Hidden String id,
      @Label("Código") String codigo,
      @Label("Nombre") String nombre,
      @Label("NIF") String nif,
      @Label("Ciudad") String ciudad) {}

  static EmpresasListing of(String stayId) {
    var listing = new EmpresasListing();
    listing.stayId = stayId;
    return listing;
  }

  public String getStayId() {
    return stayId;
  }

  public void setStayId(String stayId) {
    this.stayId = stayId;
  }

  @Override
  public ListingData<Empresa> search(SearchRequest request, HttpRequest httpRequest) {
    var rows = empresas().search(request.searchText()).stream().map(EmpresasListing::row).toList();
    return Paging.page(rows, request);
  }

  static Empresa row(Companies.Company c) {
    return new Empresa(c.code(), c.code(), c.name(), c.taxId(), c.city());
  }

  // a click on a row: that company is the stay's

  @Override
  public boolean supportsAction(String actionId) {
    return "view".equals(actionId) || Listing.super.supportsAction(actionId);
  }

  @Override
  public Object handleAction(String actionId, HttpRequest httpRequest) {
    if (!"view".equals(actionId)) {
      return Listing.super.handleAction(actionId, httpRequest);
    }
    var params = httpRequest.runActionRq().parameters();
    var code = params == null || params.get("id") == null ? null : String.valueOf(params.get("id"));
    if (code == null || stayId == null) {
      return new Message("⛔ No se sabe qué empresa ni qué reserva");
    }
    empresas().set(stayId, code, DeskUser.name());
    var nombre = empresas().of(stayId).map(io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks.Company::name)
        .orElse(code);
    return List.of(new Message("Se factura a " + nombre + " — enviado a Opera"), UICommand.closeModal(),
        UICommand.dispatchEvent(ELEGIDA));
  }

  private static StayCompanies empresas() {
    return MateuBeanProvider.getBean(StayCompanies.class);
  }
}
