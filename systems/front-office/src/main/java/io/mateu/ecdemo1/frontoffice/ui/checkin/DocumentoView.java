package io.mateu.ecdemo1.frontoffice.ui.checkin;

import io.mateu.ecdemo1.frontoffice.domain.guest.PaxKardexes;

import io.mateu.core.infra.declarative.orchestrators.editableview.EditableView;
import io.mateu.ecdemo1.frontoffice.domain.stay.Companion;
import io.mateu.ecdemo1.frontoffice.application.KardexService;
import io.mateu.ecdemo1.frontoffice.application.RegistrationRequirementsService;
import io.mateu.ecdemo1.integration.model.registration.RegistrationRequirements;
import io.mateu.ecdemo1.integration.model.registration.RegistrationRuleChanged.Field;
import io.mateu.ecdemo1.integration.model.registration.RegistrationRuleChanged.Moment;
import io.mateu.ecdemo1.frontoffice.application.StayQueries;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.PlainText;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.SubscribeTo;
import io.mateu.uidl.annotations.SubscribesTo;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.Button;
import io.mateu.uidl.data.LongTask;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.UICommand;
import io.mateu.uidl.fluent.Action;
import io.mateu.uidl.interfaces.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * The Documento block of the Identidad step as an ORCHESTRATED embedded element (a {@link
 * EditableView} island, {@code @Inline} inside the step's section): the backend decides which of
 * its three states renders —
 *
 * <ul>
 *   <li><b>Sin datos</b> — a warning notice plus a "Escanear documento" button; the scan streams a
 *       simulated progress dialog ({@link LongTask} over SSE) and, when done, verifies the identity
 *       in the domain and reloads the island into…
 *   <li><b>Hay datos</b> — a property list with the document data and the built-in Edit toolbar
 *       button;
 *   <li><b>Editor</b> — the editable form; Save persists and lands back on "hay datos" (the
 *       standard EditableView save → /view cycle).
 * </ul>
 *
 * <p>The island works on ONE pax of the reservation at a time: pax 1 is the stay's main guest
 * (Guest aggregate), pax 2..N are the stay's companions. The host seeds {@code stayId} and {@code
 * paxIndex} through the embedded field's initialData, and the registroPax band switches the pax by
 * dispatching {@code pax-seleccionado} — the subscription on the loaded models runs {@code
 * cambiarPax}, which route-flips so the embedded mediator re-renders with the new pax.
 */
@Getter
@Setter
@UI("/checkin-documento")
@Title("Documento")
@Service
@Scope("prototype")
public class DocumentoView extends EditableView<Object, DocumentoView.DocumentoEditor> {

  @Getter(AccessLevel.NONE) final StayQueries queries;
  @Getter(AccessLevel.NONE) final KardexService kardex;
  @Getter(AccessLevel.NONE) final RegistrationRequirementsService registration;

  /** A prototype bean: Mateu takes the island from Spring; the Identidad step asks Mateu for one too. */
  @Getter(AccessLevel.NONE) final io.mateu.ecdemo1.frontoffice.application.Recognition recognition;

  @Getter(AccessLevel.NONE) final io.mateu.ecdemo1.frontoffice.domain.customer.PaxRecognitions recognitions;

  public DocumentoView(StayQueries queries, KardexService kardex, RegistrationRequirementsService registration,
                       io.mateu.ecdemo1.frontoffice.application.Recognition recognition,
                       io.mateu.ecdemo1.frontoffice.domain.customer.PaxRecognitions recognitions) {
    this.recognitions = recognitions;
    this.recognition = recognition;
    this.queries = queries;
    this.kardex = kardex;
    this.registration = registration;
  }

  @Hidden String stayId;
  @Hidden int paxIndex = 1;
  @Hidden boolean vistaAlterna;

  // ── the three states' models ─────────────────────────────────────────────────

  /** Sin datos: aviso + escanear (the subscriptions reload the island after scan / pax switch). */
  @Getter
  @Setter
  @Title("Documento")
  @SubscribesTo({
    @SubscribeTo(event = "documento-escaneado", action = "reloadDocumento"),
    @SubscribeTo(event = "pax-seleccionado", action = "cambiarPax")
  })
  public static class DocumentoPendiente {
    @io.mateu.uidl.annotations.Notice(theme = "warning")
    String aviso = "Documento pendiente de escaneo";

    @Label("")
    Button escanear = new Button("Escanear documento", "escanear");

    // demo: the same guest hands over a passport the chain has never seen — the desk is offered the
    // customers they may be, and confirms with their Riu Class number or email
    @Label("")
    Button pasaporteNuevo = new Button("Simular pasaporte nuevo", "escanearPasaporteNuevo");

    @Label("")
    Button rellenar = new Button("Rellenar a mano", "edit");
  }

  /** Hay datos: read-only property list (label left / value right). */
  @Getter
  @Setter
  @Title("Documento")
  @SubscribeTo(event = "pax-seleccionado", action = "cambiarPax")
  public static class DocumentoDatos {
    @Section(value = "", propertyList = true, frameless = true)
    @Label("Documento")
    String documento;

    @Label("Nombre")
    String nombre;

    @Label("Email")
    String email;

    @Label("Teléfono")
    String telefono;

    /** What the destination's registration rules ask of this pax, and what of it the kárdex has. */
    @Label("Registro de viajeros")
    String registro;

    /** «Titular · kárdex completado en recepción» or «Acompañante · kárdex provisional». */
    @Label("Kárdex")
    String ficha;

    @Label("Dirección")
    String direccion;

    @Label("Idioma")
    String idioma;

    @Label("Nº Riu Class")
    String riuClass;

    @Label("Publicidad")
    String publicidad;

    // demo: re-scan the pax as if they handed over a new passport (see DocumentoPendiente)
    @Label("")
    Button pasaporteNuevo = new Button("Simular pasaporte nuevo", "escanearPasaporteNuevo");
  }

  /**
   * Editor: the pax's whole kárdex, in the order the desk asks for it — identity, contact, the
   * registration data the destination's rules ask for and the desk's own data. The fields the rules
   * require are marked required for this pax's nationality and age ({@link #isRequired}), and the legal
   * basis is said above them; the issuing country and the responsible adult only show when a rule asks
   * for them. Saved with a Riu Class number or an email it confirms who the pax is, and with name and
   * birth date it looks for who they may be ({@link #reconocer}).
   */
  @Getter
  @Setter
  @Title("Documento")
  @SubscribeTo(event = "pax-seleccionado", action = "cambiarPax")
  public static class DocumentoEditor implements io.mateu.uidl.interfaces.RequiredSupplier,
      io.mateu.uidl.interfaces.VisibilitySupplier {
    @Section(value = "", columns = 2, frameless = true)
    @io.mateu.uidl.annotations.Colspan(2)
    @io.mateu.uidl.annotations.Notice(theme = "info")
    String baseLegal = "";

    @Label("Apellidos")
    String apellidos;

    @Label("Nombre")
    String nombre;

    @Label("Email")
    String email;

    @Label("Nº Riu Class")
    String riuClass;

    @Label("Fecha de nacimiento")
    java.time.LocalDate fechaNacimiento;

    @Label("Sexo")
    String sexo;

    @Label("Documento")
    String documento;

    @Label("Tipo de documento")
    String tipoDocumento;

    @Label("Caducidad")
    java.time.LocalDate caducidad;

    @Label("Expedición")
    java.time.LocalDate fechaExpedicion;

    @Label("País de expedición")
    String paisExpedicion;

    @Label("Nacionalidad")
    String nacionalidad;

    @Label("Idioma")
    String idioma;

    @Label("Dirección")
    String direccion;

    @Label("Población")
    String ciudad;

    @Label("Código postal")
    String codigoPostal;

    @Label("Provincia")
    String provincia;

    @Label("Ciudad de nacimiento")
    String lugarNacimiento;

    @Label("País de residencia")
    String paisResidencia;

    @Label("Teléfono")
    String telefono;

    @Label("Fax")
    String fax;

    @Label("Adulto responsable y parentesco")
    String tutor;

    /** Pax 2..N: what the kárdex sends the MDM as a companion. */
    @io.mateu.uidl.annotations.ReadOnly
    @Label("Es acompañante")
    boolean acompanante;

    /** Until the desk saves the kárdex with the guest, it is the reservation's provisional one. */
    @io.mateu.uidl.annotations.ReadOnly
    @Label("Es cárdex provisional")
    boolean cardexProvisional;

    @Label("Acepta publicidad")
    Boolean aceptaPublicidad;

    /** The rules' required fields for this pax, comma separated (RegistrationRuleChanged.Field names). */
    @Hidden String requeridos;

    @Override
    public boolean isRequired(String fieldName, HttpRequest httpRequest) {
      var field = FIELDS.get(fieldName);
      return field != null && requeridos != null && java.util.Arrays.asList(requeridos.split(",")).contains(field.name());
    }

    /** The issuing country and the responsible adult are registration data only some rules ask for. */
    @Override
    public boolean isHidden(String fieldName, HttpRequest httpRequest) {
      return ("paisExpedicion".equals(fieldName) || "tutor".equals(fieldName)) && !isRequired(fieldName, httpRequest);
    }
  }

  /** Each editor field, and the registration field it is. */
  static final java.util.Map<String, Field> FIELDS = java.util.Map.ofEntries(
      java.util.Map.entry("documento", Field.DOCUMENT_NUMBER), java.util.Map.entry("tipoDocumento", Field.DOCUMENT_TYPE),
      java.util.Map.entry("paisExpedicion", Field.DOCUMENT_ISSUING_COUNTRY), java.util.Map.entry("caducidad", Field.DOCUMENT_EXPIRY),
      java.util.Map.entry("nacionalidad", Field.NATIONALITY), java.util.Map.entry("fechaNacimiento", Field.BIRTH_DATE),
      java.util.Map.entry("lugarNacimiento", Field.BIRTH_PLACE), java.util.Map.entry("sexo", Field.SEX),
      java.util.Map.entry("direccion", Field.ADDRESS), java.util.Map.entry("ciudad", Field.CITY),
      java.util.Map.entry("codigoPostal", Field.POSTAL_CODE), java.util.Map.entry("paisResidencia", Field.COUNTRY_OF_RESIDENCE),
      java.util.Map.entry("tutor", Field.GUARDIAN));

  // ── state selection ──────────────────────────────────────────────────────────

  @Override
  public Object view(HttpRequest httpRequest) {
    var pax = pax();
    if (!pax.complete()) {
      var pendiente = new DocumentoPendiente();
      pendiente.setAviso(
          paxIndex() == 1
              ? "Documento pendiente de escaneo"
              : "Documento del huésped " + paxIndex() + " pendiente de escaneo");
      return pendiente;
    }
    var datos = new DocumentoDatos();
    datos.setDocumento("✓ Verificado — " + pax.document());
    datos.setNombre(pax.name());
    datos.setEmail(pax.email());
    datos.setTelefono(pax.phone());
    datos.setRegistro(registro());
    var k = kardexOf();
    datos.setFicha((paxIndex() == 1 ? "Titular" : "Acompañante") + " · "
        + (k.isPresent() ? "kárdex completado en recepción" : "kárdex provisional — complétalo con el huésped"));
    if (stayId != null && !stayId.isBlank()) {
      var values = registration.values(queries.view(stayId).stay(), paxIndex());
      datos.setDireccion(address(values.get(Field.ADDRESS), values.get(Field.POSTAL_CODE), values.get(Field.CITY),
          k.map(PaxKardexes.PaxKardex::province).orElse(null), values.get(Field.COUNTRY_OF_RESIDENCE)));
    }
    k.ifPresent(x -> {
      datos.setIdioma(x.language());
      datos.setRiuClass(x.riuClass());
      datos.setPublicidad(x.marketingConsent() == null ? null : x.marketingConsent() ? "Acepta" : "No acepta");
    });
    return datos;
  }

  /** «Calle Mayor 1, 07001 Palma (Illes Balears) · ES», or null with nothing. */
  static String address(String street, String postalCode, String city, String province, String country) {
    var place = String.join(" ", java.util.stream.Stream.of(postalCode, city).filter(v -> !blank(v)).toList());
    var parts = new ArrayList<String>();
    if (!blank(street)) parts.add(street);
    if (!place.isBlank()) parts.add(place + (blank(province) ? "" : " (" + province + ")"));
    else if (!blank(province)) parts.add(province);
    var text = String.join(", ", parts);
    if (!blank(country)) text = text.isEmpty() ? country : text + " · " + country;
    return text.isEmpty() ? null : text;
  }

  java.util.Optional<PaxKardexes.PaxKardex> kardexOf() {
    return stayId == null || stayId.isBlank() ? java.util.Optional.empty() : kardex.kardexOf(stayId, paxIndex());
  }

  /** «Ana María» and «García López» from «Ana María García López»: the first word is the name, by default. */
  static String[] split(String name) {
    if (blank(name)) {
      return new String[] {null, null};
    }
    var n = name.trim();
    var space = n.indexOf(' ');
    return space < 0 ? new String[] {n, null} : new String[] {n.substring(0, space), n.substring(space + 1).trim()};
  }

  /** «Exige: nacionalidad ✓, fecha de nacimiento ✓, dirección — falta · RD 933/2021», or null with no rule. */
  String registro() {
    if (stayId == null || stayId.isBlank()) {
      return null;
    }
    var stay = queries.view(stayId).stay();
    var result = registration.required(stay, paxIndex(), Moment.CHECK_IN);
    if (result.fields().isEmpty()) {
      return "Este destino no exige más datos a este huésped";
    }
    var values = registration.values(stay, paxIndex());
    var parts = result.fields().stream().filter(f -> f != Field.SIGNATURE)
        .map(f -> RegistrationRequirements.label(f) + (blank(values.get(f)) ? " — falta" : " ✓")).toList();
    return "Exige: " + String.join(", ", parts)
        + (result.legalBases().isEmpty() ? "" : " · " + String.join("; ", result.legalBases()));
  }

  @Override
  public DocumentoEditor editor(HttpRequest httpRequest) {
    var pax = pax();
    var editor = new DocumentoEditor();
    if (pax.complete()) {
      editor.setDocumento(pax.document());
      editor.setEmail(pax.email());
      editor.setTelefono(pax.phone());
    }
    // the name as the kárdex split it; else as the scanned document did; else the first word is the name
    // (manual filling: the slot's provisional name as a starting point)
    var k = kardexOf();
    var scanned = stayId == null || stayId.isBlank() ? null : recognitions.scanOf(stayId, paxIndex()).orElse(null);
    var parts = split(pax.name());
    editor.setNombre(k.map(PaxKardexes.PaxKardex::firstName).filter(v -> !blank(v))
        .orElse(scanned != null && !blank(scanned.firstName()) ? scanned.firstName() : parts[0]));
    editor.setApellidos(k.map(PaxKardexes.PaxKardex::lastName).filter(v -> !blank(v))
        .orElse(scanned != null && !blank(scanned.lastName()) ? scanned.lastName() : parts[1]));
    editor.setAcompanante(paxIndex() > 1);
    editor.setCardexProvisional(k.isEmpty());
    k.ifPresent(x -> {
      editor.setRiuClass(x.riuClass());
      editor.setFechaExpedicion(x.documentIssueDate());
      editor.setIdioma(x.language());
      editor.setProvincia(x.province());
      editor.setFax(x.fax());
      editor.setAceptaPublicidad(x.marketingConsent());
    });
    if (stayId != null && !stayId.isBlank()) {
      var stay = queries.view(stayId).stay();
      var values = registration.values(stay, paxIndex());
      editor.setTipoDocumento(values.get(Field.DOCUMENT_TYPE));
      editor.setPaisExpedicion(values.get(Field.DOCUMENT_ISSUING_COUNTRY));
      editor.setCaducidad(date(values.get(Field.DOCUMENT_EXPIRY)));
      editor.setNacionalidad(values.get(Field.NATIONALITY));
      editor.setFechaNacimiento(date(values.get(Field.BIRTH_DATE)));
      editor.setLugarNacimiento(values.get(Field.BIRTH_PLACE));
      editor.setSexo(values.get(Field.SEX));
      editor.setDireccion(values.get(Field.ADDRESS));
      editor.setCiudad(values.get(Field.CITY));
      editor.setCodigoPostal(values.get(Field.POSTAL_CODE));
      editor.setPaisResidencia(values.get(Field.COUNTRY_OF_RESIDENCE));
      editor.setTutor(values.get(Field.GUARDIAN));
      var result = registration.required(stay, paxIndex(), Moment.CHECK_IN);
      editor.setRequeridos(String.join(",", result.fields().stream().map(Enum::name).toList()));
      editor.setBaseLegal(result.fields().isEmpty() ? ""
          : "Datos obligatorios para el registro de este huésped"
              + (result.legalBases().isEmpty() ? "" : " — " + String.join("; ", result.legalBases())));
    }
    return editor;
  }

  /** The registration data an edit carries, by field. */
  static java.util.Map<Field, String> registrationData(DocumentoEditor e) {
    var values = new java.util.EnumMap<Field, String>(Field.class);
    values.put(Field.DOCUMENT_TYPE, e.getTipoDocumento());
    values.put(Field.DOCUMENT_ISSUING_COUNTRY, upper(e.getPaisExpedicion()));
    values.put(Field.DOCUMENT_EXPIRY, e.getCaducidad() == null ? null : e.getCaducidad().toString());
    values.put(Field.NATIONALITY, upper(e.getNacionalidad()));
    values.put(Field.BIRTH_DATE, e.getFechaNacimiento() == null ? null : e.getFechaNacimiento().toString());
    values.put(Field.BIRTH_PLACE, e.getLugarNacimiento());
    values.put(Field.SEX, e.getSexo());
    values.put(Field.ADDRESS, e.getDireccion());
    values.put(Field.CITY, e.getCiudad());
    values.put(Field.POSTAL_CODE, e.getCodigoPostal());
    values.put(Field.COUNTRY_OF_RESIDENCE, upper(e.getPaisResidencia()));
    values.put(Field.GUARDIAN, e.getTutor());
    return values;
  }

  static String trim(String s) {
    return blank(s) ? null : s.trim();
  }

  static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
  }

  static java.time.LocalDate date(String s) {
    try {
      return s == null || s.isBlank() ? null : java.time.LocalDate.parse(s.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  @Override
  public void save(HttpRequest httpRequest) {
    var edited = httpRequest.getInitiatorState(DocumentoEditor.class);
    if (edited == null) {
      return;
    }
    if (stayId == null || stayId.isBlank()) {
      return;
    }
    var name = String.join(" ", java.util.stream.Stream.of(edited.getNombre(), edited.getApellidos())
        .filter(v -> !blank(v)).map(String::trim).toList());
    if (pax().complete()) {
      kardex.contactUpdated(stayId, paxIndex(), edited.getEmail(), edited.getTelefono());
    } else {
      kardex.registered(stayId, paxIndex(), edited.getDocumento(), name, edited.getEmail(), edited.getTelefono());
    }
    kardex.registrationData(stayId, paxIndex(), registrationData(edited));
    kardex.kardexFilled(stayId, paxIndex(), new PaxKardexes.PaxKardex(stayId, paxIndex(), trim(edited.getNombre()),
        trim(edited.getApellidos()), upper(edited.getRiuClass()), edited.getFechaExpedicion(),
        blank(edited.getIdioma()) ? null : edited.getIdioma().trim().toLowerCase(java.util.Locale.ROOT),
        trim(edited.getProvincia()), trim(edited.getFax()), edited.getAceptaPublicidad(), null, null),
        io.mateu.ecdemo1.frontoffice.infra.security.DeskUser.name());
  }

  static boolean changed(String before, String after) {
    return !blank(after) && !after.trim().equalsIgnoreCase(before == null ? "" : before.trim());
  }

  /**
   * Who the pax is, from the kárdex the desk just saved — what the guest told them: a Riu Class number
   * confirms the customer (certainty), as does an email the chain knows; with no certainty, name,
   * surnames and birth date look for who they may be (only possible). Null when there was nothing new
   * to ask the MDM, or nothing worth telling the desk.
   */
  String reconocer(DocumentoEditor edited, String riuClassAntes, String emailAntes) {
    var desk = io.mateu.ecdemo1.frontoffice.infra.security.DeskUser.name();
    var cliente = recognition.view(stayId, paxIndex());
    if (!blank(edited.getRiuClass()) && (!cliente.known() || changed(riuClassAntes, edited.getRiuClass()))) {
      var answer = recognition.confirm(stayId, paxIndex(), desk, edited.getRiuClass());
      return (answer.done() ? "★ " : "") + answer.message();
    }
    if (cliente.known()) {
      return null;
    }
    if (!blank(edited.getEmail()) && changed(emailAntes, edited.getEmail())) {
      var answer = recognition.confirm(stayId, paxIndex(), desk, edited.getEmail());
      if (answer.done()) {
        return "★ " + answer.message();
      }
    }
    if (!cliente.possible() && edited.getFechaNacimiento() != null
        && !(blank(edited.getNombre()) && blank(edited.getApellidos()))) {
      var answer = recognition.searchByName(stayId, paxIndex(), edited.getNombre(), edited.getApellidos(),
          edited.getFechaNacimiento());
      return answer.done() ? answer.message() : null;
    }
    return null;
  }

  /** No Edit button while there is no data — the empty state only offers the scan. */
  @Override
  public boolean readOnly() {
    return !pax().complete();
  }

  // ── actions ──────────────────────────────────────────────────────────────────

  @Override
  public List<Action> actions(HttpRequest httpRequest) {
    var actions = new ArrayList<>(super.actions(httpRequest));
    actions.add(Action.builder().id("escanear").sse(true).build());
    actions.add(Action.builder().id("escanearPasaporteNuevo").sse(true).build());
    actions.add(Action.builder().id("reloadDocumento").build());
    actions.add(Action.builder().id("cambiarPax").build());
    return actions;
  }

  @Override
  public Object handleAction(String actionId, HttpRequest httpRequest) {
    return switch (actionId) {
      case "escanear" -> escanear(false);
      case "escanearPasaporteNuevo" -> escanear(true);
      case "save" -> {
        // registering a pending pax by hand completes it like a scan: go on to the next one — unless
        // the save recognised them (the wizard stays on them to see it); an edit of a complete pax
        // stays on it
        boolean registro = !pax().complete();
        var edited = httpRequest.getInitiatorState(DocumentoEditor.class);
        boolean conStay = edited != null && stayId != null && !stayId.isBlank();
        var riuClassAntes = conStay ? kardexOf().map(PaxKardexes.PaxKardex::riuClass).orElse(null) : null;
        var emailAntes = conStay ? pax().email() : null;
        var result =
            new ArrayList<Object>((java.util.Collection<?>) super.handleAction(actionId, httpRequest));
        var aviso = conStay ? reconocer(edited, riuClassAntes, emailAntes) : null;
        if (aviso != null) {
          result.add(new io.mateu.uidl.data.Message(aviso));
        }
        result.add(UICommand.dispatchEvent("documento-escaneado", java.util.Map.of("avanzar", registro)));
        yield result;
      }
      case "reloadDocumento" -> {
        // the same next pax the wizard selects (both read it from the stay), so the band and the
        // island agree whichever reload lands last
        var avanzar = httpRequest.runActionRq().parameters() == null ? null
            : httpRequest.runActionRq().parameters().get("avanzar");
        if (Boolean.parseBoolean(String.valueOf(avanzar)) && stayId != null && !stayId.isBlank()
            && !recognition.hasNews(stayId, paxIndex())) {
          var next = queries.nextPendingPax(stayId, paxIndex());
          if (next > 0) {
            paxIndex = next;
          }
        }
        yield reload();
      }
      case "cambiarPax" -> {
        var raw = httpRequest.runActionRq().parameters().get("paxIndex");
        if (raw instanceof Number number) {
          paxIndex = number.intValue();
        } else if (raw != null) {
          paxIndex = (int) Double.parseDouble(String.valueOf(raw));
        }
        yield reload();
      }
      default -> super.handleAction(actionId, httpRequest);
    };
  }

  /**
   * The scan, as a progress dialog over SSE; done, the island and the wizard go on to the next pax
   * still lacking identity — unless the scan recognised this one (they stay on it to see it).
   * {@code pasaporte}: the demo's new passport of the same person.
   */
  private Object escanear(boolean pasaporte) {
    return LongTask.create(pasaporte ? "Escaneando pasaporte…" : "Escaneando documento…")
        .withProgressBar()
        .done("Documento verificado", "Identidad leída del documento")
        .closeAfter(1)
        // avanzar: the wizard and this island go on to the next pax still lacking identity
        .withCommand(UICommand.dispatchEvent("documento-escaneado", java.util.Map.of("avanzar", true)))
        .run(
            progress ->
                Flux.range(1, 4)
                    .delayElements(Duration.ofMillis(450))
                    .map(
                        i -> {
                          if (i == 4 && stayId != null && !stayId.isBlank()) {
                            if (pasaporte) {
                              kardex.scannedNewPassport(stayId, paxIndex());
                            } else {
                              kardex.scanned(stayId, paxIndex());
                            }
                          }
                          return progress.step(SCAN_STEPS[i - 1], i / 4.0);
                        }));
  }

  /** The embedded mediator only re-renders on a route change — alternate the view route. */
  private Object reload() {
    vistaAlterna = !vistaAlterna;
    setRouteTo(vistaAlterna ? "/view" : "/");
    return new State(this);
  }

  private static final String[] SCAN_STEPS = {
    "Encendiendo el escáner…", "Leyendo el documento…", "Extrayendo los datos…", "Verificando la identidad…"
  };

  // ── the pax the island is working on (main guest or companion) ───────────────

  private int paxIndex() {
    return paxIndex < 1 ? 1 : paxIndex;
  }

  private Pax pax() {
    return paxIndex() == 1 ? new MainGuestPax() : new CompanionPax(paxIndex());
  }

  /** Uniform identity/contact access over the main guest (pax 1) and the companions (pax 2..N). */
  private interface Pax {
    boolean complete();

    String document();

    String name();

    String email();

    String phone();
  }

  private class MainGuestPax implements Pax {
    private io.mateu.ecdemo1.frontoffice.domain.guest.Guest guest() {
      if (stayId == null || stayId.isBlank()) {
        return null;
      }
      return queries.view(stayId).guest();
    }

    @Override
    public boolean complete() {
      var guest = guest();
      return guest != null && guest.identityComplete();
    }

    @Override
    public String document() {
      var guest = guest();
      return guest == null ? null : guest.document();
    }

    @Override
    public String name() {
      var guest = guest();
      return guest == null ? null : guest.name();
    }

    @Override
    public String email() {
      var guest = guest();
      return guest == null ? null : guest.email();
    }

    @Override
    public String phone() {
      var guest = guest();
      return guest == null ? null : guest.phone();
    }
  }

  private class CompanionPax implements Pax {
    private final int number;

    private CompanionPax(int number) {
      this.number = number;
    }

    private Companion companion() {
      if (stayId == null || stayId.isBlank()) {
        return null;
      }
      return queries.view(stayId).stay().companionAt(number);
    }

    @Override
    public boolean complete() {
      var companion = companion();
      return companion != null && companion.identityComplete();
    }

    @Override
    public String document() {
      var companion = companion();
      return companion == null ? null : companion.document();
    }

    @Override
    public String name() {
      var companion = companion();
      return companion == null ? "Huésped " + number : companion.name();
    }

    @Override
    public String email() {
      var companion = companion();
      return companion == null ? null : companion.email();
    }

    @Override
    public String phone() {
      var companion = companion();
      return companion == null ? null : companion.phone();
    }
  }
}
