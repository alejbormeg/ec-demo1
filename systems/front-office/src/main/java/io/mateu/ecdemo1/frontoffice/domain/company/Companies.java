package io.mateu.ecdemo1.frontoffice.domain.company;

import java.util.List;
import java.util.Optional;

/**
 * The companies a stay can be billed to — who pays when neither the agency nor the guest does: the
 * partners of type Company of the master of partners (the ERP). The desk finds one, reads it and
 * corrects its data; the master keeps it, and projects it to the PMS.
 */
public interface Companies {

  /** A company as its master has it: its code, name, tax id, address and contact. */
  record Company(String code, String name, String taxId, String addressLine, String city, String postalCode,
                 String countryCode, String email, String phone) {

    /** «Acme Corporation Ltd · B12345678 · Calle Mayor 1, 28013 Madrid (ES)», for an invoice's «Facturar a». */
    public String billTo() {
      var parts = new java.util.ArrayList<String>();
      parts.add(name);
      if (taxId != null && !taxId.isBlank()) parts.add(taxId);
      var place = String.join(" ", java.util.stream.Stream.of(postalCode, city).filter(v -> v != null && !v.isBlank()).toList());
      var address = String.join(", ", java.util.stream.Stream.of(addressLine, place).filter(v -> v != null && !v.isBlank()).toList());
      if (!address.isBlank()) parts.add(address + (countryCode == null || countryCode.isBlank() ? "" : " (" + countryCode + ")"));
      return String.join(" · ", parts);
    }
  }

  /** The master does not answer, or refused a change: what to tell the desk. */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The companies whose code or name matches the text (all, blank), at most a page. */
  List<Company> search(String text);

  Optional<Company> byCode(String code);

  /** The company's data corrected at the desk: its master keeps it (and projects it to the PMS). */
  void update(Company company);
}
