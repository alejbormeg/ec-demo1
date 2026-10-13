package io.mateu.ecdemo1.frontoffice.ui.reservas;

import io.mateu.ecdemo1.frontoffice.domain.company.Companies;
import io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.ButtonStyle;
import io.mateu.uidl.data.Drawer;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FormField;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «Empresa»: who the stay is billed to when neither the agency nor the guest pays — one of the header's
 * facts (EMPRESA, next to PRECIO CRS, TOTAL OPERA, AGENCIA), and the toolbar's «✎ Empresa» opens its DRAWER:
 * find a company of the ERP and set it, take it away, or correct the data of the one set (the ERP keeps them). The
 * drawer's actions post its own state: the stay and what the desk typed travel in its initialData.
 */
final class EmpresaPanel {

  static final String DRAWER_ID = "drawer-empresa";
  /** The fields of the company's data in the drawer's state: {@code emp_} + the company's property. */
  static final String PREFIJO = "emp_";

  private EmpresaPanel() {}

  /**
   * The drawer to choose a company: a search of the ERP's companies, each found with its button to set
   * it. {@code encontradas} null: not searched yet.
   */
  static Drawer busqueda(String stayId, String busqueda, List<Companies.Company> encontradas, PmsLinks.Company enLaReserva) {
    var contenido = new ArrayList<Component>();
    var initialData = new HashMap<String, Object>();
    initialData.put("stayId", stayId);
    initialData.put("empresaBusqueda", busqueda == null ? "" : busqueda);
    contenido.add(FormField.builder().id("empresaBusqueda").label("Nombre o código de la empresa")
        .dataType(FieldDataType.string).style("width: 100%;").build());
    contenido.add(Button.builder().id("buscar-empresas").label("Buscar").actionId("buscarEmpresas")
        .buttonStyle(ButtonStyle.primary).build());
    if (encontradas != null) {
      if (encontradas.isEmpty()) {
        contenido.add(Text.builder().text("Ninguna empresa del ERP coincide").style("margin: 0; opacity: .7;").build());
      }
      if (!encontradas.isEmpty()) {
        // a listing: a click on a company's row sets it (the row's id travels as _item)
        contenido.add(io.mateu.uidl.data.StatusList.builder()
            .rowActionId("elegirEmpresa")
            .compact(true)
            .style("width: 100%;")
            .items(encontradas.stream().map(c -> io.mateu.uidl.data.StatusItem.builder()
                .id(c.code())
                .title(c.name())
                .description(String.join(" · ", java.util.stream.Stream.of(c.code(), c.taxId(), c.city())
                    .filter(v -> v != null && !v.isBlank()).toList()))
                .build()).toList())
            .build());
      }
    }
    return Drawer.builder()
        .id(DRAWER_ID)
        .headerTitle(enLaReserva == null || enLaReserva.name() == null ? "Empresa — a quién se factura"
            : "Cambiar la empresa — ahora " + enLaReserva.name())
        .width("36rem")
        .content(VerticalLayout.builder().style("width: 100%; gap: .5rem;").content(contenido).build())
        .initialData(initialData)
        .build();
  }

  /**
   * The drawer of the stay's company: its data to correct (the ERP keeps them) — or, when the ERP does
   * not answer, why not —, and the buttons that save them, take the company away, or change it.
   */
  static Drawer datos(String stayId, Companies.Company actual, PmsLinks.Company enLaReserva) {
    var contenido = new ArrayList<Component>();
    var initialData = new HashMap<String, Object>();
    initialData.put("stayId", stayId);
    if (actual == null) {
      contenido.add(Text.builder().text("El ERP no responde ahora: los datos de la empresa no se pueden ver ni cambiar")
          .style("margin: 0; opacity: .7;").build());
    } else {
      initialData.put(PREFIJO + "code", actual.code());
      var campos = new ArrayList<Component>();
      for (var c : CAMPOS) {
        initialData.put(PREFIJO + c[0], valor(actual, c[0]));
        campos.add(FormField.builder().id(PREFIJO + c[0]).label(c[1]).dataType(FieldDataType.string)
            .required("name".equals(c[0])).style("width: 100%;").build());
      }
      contenido.add(io.mateu.uidl.data.FormLayout.builder().maxColumns(2).content(campos).style("width: 100%;").build());
      contenido.add(Button.builder().id("guardar-empresa").label("Guardar datos en el ERP").actionId("guardarEmpresa")
          .buttonStyle(ButtonStyle.primary).build());
    }
    contenido.add(Button.builder().id("cambiar-empresa").label("Cambiar de empresa").actionId("cambiarEmpresa").build());
    contenido.add(Button.builder().id("quitar-empresa").label("Quitar la empresa").actionId("quitarEmpresa").build());
    return Drawer.builder()
        .id(DRAWER_ID)
        .headerTitle("Empresa — " + (actual == null ? enLaReserva.name() : actual.name()) + " · " + enLaReserva.code())
        .width("40rem")
        .content(VerticalLayout.builder().style("width: 100%; gap: .5rem;").content(contenido).build())
        .initialData(initialData)
        .build();
  }

  /** The company's data the drawer edits: its property and label. */
  static final String[][] CAMPOS = {
      {"name", "Nombre"}, {"taxId", "CIF / NIF"}, {"addressLine", "Dirección"}, {"city", "Población"},
      {"postalCode", "Código postal"}, {"countryCode", "País (ISO)"}, {"email", "Email"}, {"phone", "Teléfono"},
  };

  static String valor(Companies.Company c, String campo) {
    var v = switch (campo) {
      case "name" -> c.name();
      case "taxId" -> c.taxId();
      case "addressLine" -> c.addressLine();
      case "city" -> c.city();
      case "postalCode" -> c.postalCode();
      case "countryCode" -> c.countryCode();
      case "email" -> c.email();
      case "phone" -> c.phone();
      default -> null;
    };
    return v == null ? "" : v;
  }

  /** The company's data as the drawer posted them. */
  static Companies.Company posted(Map<String, Object> estado) {
    java.util.function.Function<String, String> f = campo -> {
      var v = estado == null ? null : estado.get(PREFIJO + campo);
      return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    };
    return new Companies.Company(f.apply("code"), f.apply("name"), f.apply("taxId"), f.apply("addressLine"),
        f.apply("city"), f.apply("postalCode"), f.apply("countryCode"), f.apply("email"), f.apply("phone"));
  }
}
