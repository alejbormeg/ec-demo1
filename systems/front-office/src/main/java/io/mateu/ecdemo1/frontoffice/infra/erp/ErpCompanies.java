package io.mateu.ecdemo1.frontoffice.infra.erp;

import io.mateu.ecdemo1.frontoffice.domain.company.Companies;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The companies of the master of partners — the ERP ({@code frontoffice.erp-url}) —, by its REST API:
 * {@code GET /partners?type=Company}, {@code GET /partners/{code}} and {@code PUT /partners/{code}}, the
 * partner's whole details (its type and billing mode as the ERP has them). Answers read as maps (Boot 4's
 * RestClient reads with Jackson 3). Short timeouts: the desk is waiting. Not configured or not answering:
 * no companies to choose from, and a change is {@link Companies.Unavailable}.
 */
@Component
public class ErpCompanies implements Companies {

  static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
  static final ParameterizedTypeReference<Map<String, Object>> ONE = new ParameterizedTypeReference<>() {};

  final RestClient erp;

  public ErpCompanies(@Value("${frontoffice.erp-url:}") String erpUrl) {
    if (erpUrl == null || erpUrl.isBlank()) {
      this.erp = null;
      return;
    }
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.erp = RestClient.builder().baseUrl(erpUrl).requestFactory(factory).build();
  }

  @Override
  public List<Company> search(String text) {
    if (erp == null) {
      return List.of();
    }
    try {
      var found = erp.get().uri(b -> b.path("/partners").queryParam("type", "Company")
          .queryParam("search", text == null ? "" : text.trim()).queryParam("size", 30).build())
          .retrieve().body(LIST);
      var companies = new ArrayList<Company>();
      if (found != null) {
        found.forEach(p -> companies.add(company(p)));
      }
      return companies;
    } catch (RestClientException e) {
      return List.of();
    }
  }

  @Override
  public Optional<Company> byCode(String code) {
    if (erp == null || code == null || code.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(erp.get().uri("/partners/{code}", code).retrieve().body(ONE)).map(ErpCompanies::company);
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }

  @Override
  public void update(Company company) {
    if (erp == null) {
      throw new Unavailable("El ERP no está configurado: los datos de la empresa no se pueden cambiar aquí", null);
    }
    try {
      var current = erp.get().uri("/partners/{code}", company.code()).retrieve().body(ONE);
      if (current == null) {
        throw new Unavailable("El ERP no tiene la empresa " + company.code(), null);
      }
      var address = new LinkedHashMap<String, Object>();
      address.put("line", company.addressLine());
      address.put("city", company.city());
      address.put("postalCode", company.postalCode());
      address.put("countryCode", company.countryCode() == null ? null : company.countryCode().trim().toUpperCase());
      var details = new LinkedHashMap<String, Object>();
      details.put("type", current.getOrDefault("type", "Company"));
      details.put("name", company.name());
      details.put("taxId", company.taxId());
      details.put("address", address);
      details.put("email", company.email());
      details.put("phone", company.phone());
      details.put("billingMode", current.getOrDefault("billingMode", "Front"));
      erp.put().uri("/partners/{code}", company.code()).body(details).retrieve().toBodilessEntity();
    } catch (HttpClientErrorException e) {
      throw new Unavailable("El ERP no acepta los datos: " + reason(e), e);
    } catch (RestClientException e) {
      throw new Unavailable("El ERP no responde: los datos de la empresa no se han guardado. Vuelve a intentarlo en un momento", e);
    }
  }

  static String reason(HttpClientErrorException e) {
    var body = e.getResponseBodyAsString();
    var detail = body.replaceAll("(?s).*\"detail\"\\s*:\\s*\"([^\"]*)\".*", "$1");
    return detail.equals(body) ? e.getStatusText() : detail;
  }

  @SuppressWarnings("unchecked")
  static Company company(Map<String, Object> p) {
    var address = p.get("address") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.<String, Object>of();
    return new Company(text(p, "code"), text(p, "name"), text(p, "taxId"), text(address, "line"), text(address, "city"),
        text(address, "postalCode"), text(address, "countryCode"), text(p, "email"), text(p, "phone"));
  }

  static String text(Map<String, Object> map, String field) {
    var value = map == null ? null : map.get(field);
    return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
  }
}
