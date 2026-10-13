package io.mateu.ecdemo1.frontoffice.application;

import io.mateu.ecdemo1.frontoffice.domain.folio.FolioLine;
import io.mateu.ecdemo1.frontoffice.infra.pms.StayInvoices;
import io.mateu.ecdemo1.frontoffice.infra.pms.StayInvoices.StayInvoice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The invoice of a closed stay, for the desk to open («Abrir factura»). The PMS is the master of the
 * folio: when Opera gave its document at the check-out, that is the invoice, as Opera issued it. When
 * it did not — the tenant stores no printable folio, or the check-out has not reached Opera yet — the
 * desk gets the front office's own folio as a PDF, labelled «Factura proforma (front office)» on every
 * page: it is not the PMS's invoice, and says so (with the PMS's number and total, when Opera gave
 * those).
 *
 * <p>The document is served by the front office, never by a link to Opera; the link carries a
 * signature that expires ({@link #link}), since the page that shows it is behind the desk's login and
 * the document is not.
 */
@Service
public class Invoices {

  /** A document to send: its bytes, what it is, and a file name. */
  public record Document(byte[] pdf, boolean fromThePms, String fileName) {}

  /** What the desk is shown of a closed stay's invoice. */
  public record Summary(boolean fromThePms, String label) {}

  static final Duration LINK_LIFETIME = Duration.ofHours(8);
  static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  final StayQueries queries;
  final StayInvoices invoices;
  final String hotel;
  final byte[] key;
  final Clock clock = Clock.systemUTC();

  /**
   * Who the stay is billed to besides the guest, when it has a company; none in a test that does not wire
   * it. A final holder, set once: pages hold this service, and Mateu walks a page's non-final fields.
   */
  private final java.util.concurrent.atomic.AtomicReference<StayCompanies> companies = new java.util.concurrent.atomic.AtomicReference<>();

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCompanies(StayCompanies companies) {
    this.companies.set(companies);
  }

  public Invoices(StayQueries queries, StayInvoices invoices, @Value("${frontoffice.hotel:MRU01}") String hotel,
      @Value("${frontoffice.link-secret:}") String secret) {
    this.queries = queries;
    this.invoices = invoices;
    this.hotel = hotel;
    if (secret == null || secret.isBlank()) {
      this.key = new byte[32];
      new SecureRandom().nextBytes(this.key);
    } else {
      this.key = secret.getBytes(StandardCharsets.UTF_8);
    }
  }

  public Summary summary(String stayId) {
    var invoice = invoices.of(stayId);
    if (invoice.isPresent() && invoice.get().hasDocument()) {
      return new Summary(true, "Abrir factura" + (invoice.get().number() == null ? "" : " · Opera " + invoice.get().number()));
    }
    return new Summary(false, "Abrir factura proforma (front office)" + invoice.filter(i -> i.amount() != null)
        .map(i -> " · Opera " + (i.number() == null ? "" : i.number() + " ") + amount(i.amount(), i.currency())
            + (i.amount().compareTo(frontOfficeTotal(stayId)) == 0 ? ", coincide" : ", front office "
                + frontOfficeTotal(stayId).toPlainString()))
        .orElse(""));
  }

  /** What the front office's folio adds up to: its charges that count (not included, not voided). */
  BigDecimal frontOfficeTotal(String stayId) {
    var folio = queries.find(stayId).isEmpty() ? null : queries.view(stayId).folio();
    return folio == null ? BigDecimal.ZERO : folio.balance();
  }

  /** Where the desk opens the invoice: the front office's own, signed for a few hours. */
  public String link(String stayId) {
    var expires = clock.instant().plus(LINK_LIFETIME).getEpochSecond();
    return "/invoices/" + stayId + "?e=" + expires + "&s=" + sign(stayId, expires);
  }

  public boolean valid(String stayId, long expires, String signature) {
    return signature != null && expires >= clock.instant().getEpochSecond()
        && MessageDigest.isEqual(sign(stayId, expires).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII));
  }

  String sign(String stayId, long expires) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding()
          .encodeToString(mac.doFinal((stayId + "|" + expires).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The stay's invoice: the PMS's document, or the front office's proforma. Empty for a stay it does not have. */
  public Optional<Document> document(String stayId) {
    var found = queries.find(stayId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    var invoice = invoices.of(stayId);
    if (invoice.isPresent() && invoice.get().hasDocument()) {
      return Optional.of(new Document(invoice.get().pdf(), true,
          "factura-" + (invoice.get().number() == null ? stayId : invoice.get().number()) + ".pdf"));
    }
    return Optional.of(new Document(proforma(stayId, invoice.orElse(null)), false, "proforma-" + stayId + ".pdf"));
  }

  /** The company the stay is billed to, as an invoice names it — its data as the ERP has them, or its name. */
  java.util.Optional<String> billTo(String stayId) {
    var stayCompanies = companies.get();
    if (stayCompanies == null) {
      return java.util.Optional.empty();
    }
    var company = stayCompanies.of(stayId).orElse(null);
    if (company == null || company.name() == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(stayCompanies.details(stayId).map(io.mateu.ecdemo1.frontoffice.domain.company.Companies.Company::billTo)
        .orElse(company.name()));
  }

  /** The front office's folio as a PDF, labelled as the proforma it is. */
  byte[] proforma(String stayId, StayInvoice pms) {
    var view = queries.view(stayId);
    var stay = view.stay();
    var lines = new ArrayList<String[]>();
    var total = BigDecimal.ZERO;
    for (var line : view.folio() == null ? List.<FolioLine>of() : view.folio().lines()) {
      lines.add(new String[]{line.concept() + (line.voided() ? " (" + euros(line.amount()) + ", anulado)" : ""),
          line.included() ? (line.includedLabel() == null ? "incluido" : line.includedLabel())
              : line.voided() ? "anulado" : euros(line.amount())});
      if (line.counts()) {
        total = total.add(line.amount());
      }
    }
    var text = new ArrayList<String>();
    text.add("Hotel " + hotel + " · estancia " + stayId);
    text.add("Huésped: " + (view.guest() == null ? "" : view.guest().name()));
    billTo(stayId).ifPresent(company -> text.add("Facturar a: " + company));
    text.add("Estancia: " + DAY.format(stay.checkIn()) + " – " + DAY.format(stay.checkOut())
        + (stay.roomNumber() == null ? "" : " · habitación " + stay.roomNumber()));
    if (pms != null && pms.number() != null) {
      text.add("Factura de Opera: " + pms.number() + (pms.amount() == null ? "" : " por " + pms.amount().toPlainString()
          + " " + (pms.currency() == null ? "" : pms.currency())) + " — Opera no ha dado su documento.");
    }
    var totals = totals(total, pms);
    try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
      var page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var content = new PDPageContentStream(document, page)) {
        float y = 790;
        y = write(content, bold, 16, 50, y, "FACTURA PROFORMA (front office)");
        y = write(content, regular, 9, 50, y - 4,
            "No es la factura del PMS: Opera, el maestro del folio, no ha dado su documento. Folio del front office.");
        y -= 12;
        for (var t : text) {
          y = write(content, regular, 11, 50, y, t);
        }
        y -= 12;
        y = write(content, bold, 11, 50, y, "Concepto");
        write(content, bold, 11, 430, y + 15, "Importe");
        for (var line : lines) {
          y = write(content, regular, 11, 50, y, line[0]);
          write(content, regular, 11, 430, y + 15, line[1]);
          if (y < 80) {
            break;
          }
        }
        y -= 8;
        y = write(content, bold, 12, 50, y, "Total del front office");
        write(content, bold, 12, 430, y + 16, euros(total));
        if (pms != null && pms.amount() != null) {
          y = write(content, bold, 12, 50, y, "Total de la factura de Opera " + (pms.number() == null ? "" : pms.number()));
          write(content, bold, 12, 430, y + 16, amount(pms.amount(), pms.currency()));
        }
        for (var t : totals) {
          y = write(content, regular, 9, 50, y - 2, t);
        }
        write(content, regular, 8, 50, 40, "Factura proforma (front office) · generada " + DateTimeFormatter.ISO_INSTANT
            .format(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
      }
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * What the two totals say. Every charge of the desk went onto Opera's folio (registrar-cargo), and its
   * voids too, so Opera's invoice covers them: the totals match. When they do not, why — in words the
   * desk can act on.
   */
  static List<String> totals(BigDecimal frontOffice, StayInvoice pms) {
    if (pms == null || pms.amount() == null) {
      return List.of("Opera no ha dado aún el importe de su factura: solo el total del front office.");
    }
    if (pms.amount().compareTo(frontOffice) == 0) {
      return List.of("Los totales coinciden: los cargos de recepción están en el folio de Opera.");
    }
    var difference = frontOffice.subtract(pms.amount());
    return List.of("Los totales NO coinciden (diferencia " + difference.toPlainString() + "). Causas habituales: el alojamiento,",
        "que Opera factura con su tarifa y sus noches (en una salida anticipada, solo las pasadas) y con el paquete del",
        "régimen aparte (el desayuno BRKFST, que el front office no tiene en su folio); un cargo que Opera rechazó o que",
        "llegó después del check-out; o cargos del front office anteriores a subirlos a Opera.");
  }

  static String amount(BigDecimal amount, String currency) {
    return currency == null ? euros(amount)
        : String.format(Locale.forLanguageTag("es-ES"), "%,.2f %s", amount, currency);
  }

  static float write(PDPageContentStream content, PDType1Font font, float size, float x, float y, String text)
      throws IOException {
    content.beginText();
    content.setFont(font, size);
    content.newLineAtOffset(x, y);
    content.showText(printable(text));
    content.endText();
    return y - size - 4;
  }

  /** What the standard fonts can draw (WinAnsi): anything else becomes «?». */
  static String printable(String text) {
    if (text == null) {
      return "";
    }
    var out = new StringBuilder();
    for (var c : text.toCharArray()) {
      out.append(c == '–' || c == '—' || c == '·' || c == '€' || c < 0x100 && c >= 0x20 ? c : '?');
    }
    return out.toString();
  }

  static String euros(BigDecimal amount) {
    return amount == null ? "" : String.format(Locale.forLanguageTag("es-ES"), "%,.2f €", amount);
  }
}
