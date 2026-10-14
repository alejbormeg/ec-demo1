package io.mateu.ecdemo1.pmsintegration.clients;

import io.mateu.ecdemo1.integration.model.reservation.Person;
import io.mateu.ecdemo1.integration.model.customer.IdentityRequest;
import io.mateu.ecdemo1.integration.model.customer.ResolvedIdentity;
import io.mateu.ecdemo1.integration.model.mapping.Cause;
import io.mateu.ecdemo1.integration.model.mapping.CodeType;
import io.mateu.ecdemo1.integration.model.mapping.Translation;
import io.mateu.ecdemo1.integration.model.partner.Partner;
import io.mateu.ecdemo1.integration.model.reservation.Reservation;
import io.mateu.ecdemo1.pmsintegration.config.PmsIntegrationProperties;
import io.mateu.ecdemo1.pmsintegration.config.TolerantReader;
import io.mateu.workflow.dtos.Variable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * The rest of the integration, in its own terms: the CRS adapter for what to write, the mapping
 * for what the CRS's codes are in Opera and for registering what blocks a write.
 */
@Component
public class IntegrationClients {

    public record CodeRef(CodeType type, String code) {
    }

    public record Resolved(List<Translation> translations, List<Cause> missing) {

        public String target(CodeType type, String code) {
            return translation(type, code).targetCode();
        }

        public Translation translation(CodeType type, String code) {
            return translations.stream().filter(t -> t.type() == type && t.sourceCode().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No translation for %s %s".formatted(type, code)));
        }
    }

    public record PartnerProfile(String partnerCode, String profileType, String pmsProfileId, long projectedVersion) {
    }

    final RestClient crs;
    final RestClient mapping;
    final RestClient mdm;

    public IntegrationClients(PmsIntegrationProperties properties, TolerantReader reader) {
        this(properties, reader, RestClient.builder());
    }

    /**
     * Built from Boot's builder, which observes every call: the CRS adapter's, the mapping's and the
     * MDM's spans join the trace of the step that asked, and each call carries its traceparent.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationClients(PmsIntegrationProperties properties, TolerantReader reader, RestClient.Builder builder) {
        this.crs = client(builder.clone(), properties.crsIntegrationUrl(), reader);
        this.mapping = client(builder.clone(), properties.mappingUrl(), reader);
        this.mdm = client(builder.clone(), properties.customerMdmUrl(), reader);
    }

    public Reservation reservation(String hotelCode, String locator) {
        return crs.get().uri("/reservations/{hotel}/{locator}", hotelCode, locator).retrieve().body(Reservation.class);
    }

    /** Who the passengers are, as the customer MDM resolves them (HLA CRM-MDM, F001). */
    /**
     * The customer as the MDM projects it from Salesforce, the master: what a guest profile and a
     * kardex are written with. Empty if the MDM does not answer — the reservation's data then.
     */
    public java.util.Optional<Person> customer(String customerId) {
        try {
            var c = mdm.get().uri("/customers/{id}", customerId).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
            if (c == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Person(text(c, "firstName"), text(c, "lastName"),
                    io.mateu.ecdemo1.integration.model.reservation.GuestType.ADULT, null, text(c, "email"), text(c, "phone"),
                    text(c, "nationality"), text(c, "birthDate") == null ? null : java.time.LocalDate.parse(text(c, "birthDate")),
                    text(c, "documentType"), text(c, "documentNumber")));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** Tells the MDM where the customer is known: an Opera profile, a front office's guest. Best effort. */
    public void xref(String customerId, String target, String reference, String context) {
        try {
            mdm.put().uri("/customers/{id}/xrefs", customerId)
                    .body(java.util.Map.of("target", target, "reference", reference, "context", context == null ? "" : context))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // The next projection tells it again.
        }
    }

    /**
     * The customer an Opera guest profile is, as the MDM's cross references say — the survivor, if it
     * was merged since. Empty when the MDM does not know the profile (one born in Opera), or does not
     * answer: the stay then goes as Opera has it.
     */
    public java.util.Optional<String> customerByPmsProfile(String profileId) {
        try {
            var found = mdm.get().uri(b -> b.path("/customers").queryParam("xref", "OPERA:" + profileId).build())
                    .retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
            return found == null || !found.isArray() || found.isEmpty() ? java.util.Optional.empty()
                    : java.util.Optional.ofNullable(text(found.get(0), "id"));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    public List<ResolvedIdentity> identities(IdentityRequest request) {
        return mdm.post().uri("/identities/resolve").body(request).retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    public Partner partner(String code) {
        return crs.get().uri("/partners/{code}", code).retrieve().body(Partner.class);
    }

    public Resolved resolve(String hotelCode, List<CodeRef> codes) {
        return mapping.post().uri("/resolve").body(new ResolveRequest(hotelCode, codes)).retrieve().body(Resolved.class);
    }

    public Optional<PartnerProfile> partnerProfile(String partnerCode) {
        try {
            return Optional.ofNullable(mapping.get().uri("/partner-profiles/{code}", partnerCode).retrieve().body(PartnerProfile.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * The partner (its code) a PMS profile is, as the mapping recorded it when it projected the partner;
     * empty when no partner is — or the mapping does not answer: the stay goes without the code.
     */
    public Optional<String> partnerOfProfile(String pmsProfileId) {
        try {
            return Optional.ofNullable(mapping.get().uri("/partner-profiles?pmsProfileId={id}", pmsProfileId).retrieve()
                    .body(PartnerProfile.class)).map(PartnerProfile::partnerCode);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void await(String processKey, String definitionId, String hotelCode, String subject, List<Variable> variables,
                      List<Cause> causes) {
        mapping.post().uri("/causes/wait")
                .body(new WaitRequest(processKey, definitionId, hotelCode, subject, variables, causes))
                .retrieve().toBodilessEntity();
    }

    /** Resolves a cause if it is open — whatever waited only on it goes on; nothing if it is not open. */
    public void resolveCauseIfOpen(String causeKey, String by) {
        mapping.post().uri(b -> b.path("/causes/resolve-if-open").queryParam("key", causeKey).queryParam("by", by).build())
                .retrieve().toBodilessEntity();
    }

    record ResolveRequest(String hotelCode, List<CodeRef> codes) {
    }

    record WaitRequest(String processKey, String definitionId, String hotelCode, String subject, List<Variable> variables,
                       List<Cause> causes) {
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, TolerantReader reader) {
        return builder
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.addFirst(new MappingJackson2HttpMessageConverter(reader.mapper()));
                })
                .build();
    }
}
