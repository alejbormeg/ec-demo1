package io.mateu.ecdemo1.frontoffice.application;

import io.mateu.ecdemo1.frontoffice.domain.company.Companies;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayRepository;
import io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks;
import io.mateu.ecdemo1.frontoffice.infra.pms.ReceptionReports;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company a stay is billed to when neither the agency nor the guest pays: a partner of type Company
 * of the master of partners (the ERP). The desk sets it, changes it or takes it away — the PMS is told
 * in the same transaction (its reservation gets the company's profile, and the stay routed to it) —, and
 * corrects the company's data, which its master keeps. Each audited.
 */
@Service
public class StayCompanies {

  final StayRepository stays;
  final PmsLinks links;
  final Companies companies;
  final StayAudit audit;
  final ObjectProvider<ReceptionReports> reports;

  public StayCompanies(StayRepository stays, PmsLinks links, Companies companies, StayAudit audit,
                       ObjectProvider<ReceptionReports> reports) {
    this.stays = stays;
    this.links = links;
    this.companies = companies;
    this.audit = audit;
    this.reports = reports;
  }

  /** The stay's company: its code and name as the stay keeps them. */
  public Optional<PmsLinks.Company> of(String stayId) {
    return links.companyOf(stayId);
  }

  /** The stay's company as its master has it now — for the desk to read and correct. */
  public Optional<Companies.Company> details(String stayId) {
    return of(stayId).map(PmsLinks.Company::code).flatMap(companies::byCode);
  }

  public List<Companies.Company> search(String text) {
    return companies.search(text);
  }

  /** The company the stay is billed to — or none ({@code code} null) —, as the desk chose it. */
  @Transactional
  public void set(String stayId, String code, String by) {
    var company = code == null || code.isBlank() ? null
        : companies.byCode(code).orElseThrow(() -> new NoSuchElementException("El ERP no tiene la empresa " + code));
    audit.run(company == null ? "Company removed" : "Company set", stayId, by,
        StayAudit.params("company", company == null ? "-" : company.code()), () -> {
          stays.findById(stayId).orElseThrow(() -> new NoSuchElementException("Reserva " + stayId + " no encontrada"));
          links.companySetAtDesk(stayId, company == null ? null : company.code(), company == null ? null : company.name());
          var pms = reports.getIfAvailable();
          if (pms != null) {
            pms.companyChanged(stayId, company == null ? null : company.code(), company == null ? null : company.name(), by);
          }
          return true;
        }, ok -> company == null ? "Reserva sin empresa" : "Factura a " + company.name());
  }

  /** The company's data corrected at the desk: its master keeps it; the stay shows the new name. */
  @Transactional
  public void correct(String stayId, Companies.Company company, String by) {
    if (company.name() == null || company.name().isBlank()) {
      throw new IllegalArgumentException("La empresa necesita un nombre");
    }
    audit.run("Company corrected", stayId, by, StayAudit.params("company", company.code()), () -> {
      companies.update(company);
      links.companyOf(stayId).filter(c -> company.code().equals(c.code()))
          .ifPresent(c -> links.companyName(stayId, company.name()));
      return true;
    }, ok -> "Datos de " + company.name() + " guardados en el ERP");
  }
}
