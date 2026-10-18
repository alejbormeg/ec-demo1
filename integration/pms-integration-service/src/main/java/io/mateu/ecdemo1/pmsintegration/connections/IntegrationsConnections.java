package io.mateu.ecdemo1.pmsintegration.connections;

import io.mateu.ecdemo1.integration.model.integration.IntegrationView;
import io.mateu.ecdemo1.integration.model.integration.OhipConnection;
import io.mateu.ecdemo1.pmsintegration.config.PmsIntegrationProperties;
import io.mateu.ecdemo1.pmsintegration.config.TolerantReader;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connections, from the integrations service — kept for a short while, so a burst of writes to
 * one property asks once, and a credential changed in the console is picked up within that while.
 */
@Component
public class IntegrationsConnections implements Connections {

    static final Duration KEEP = Duration.ofSeconds(30);

    record Kept(Optional<OhipConnection> connection, Instant at) {
    }

    final RestClient integrations;
    final Clock clock;
    final Map<String, Kept> kept = new ConcurrentHashMap<>();

    public IntegrationsConnections(PmsIntegrationProperties properties, TolerantReader reader, Clock clock) {
        this(properties, reader, clock, RestClient.builder());
    }

    /** Built from Boot's builder, which observes every call: it carries the trace of the step that asked. */
    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationsConnections(PmsIntegrationProperties properties, TolerantReader reader, Clock clock,
                                   RestClient.Builder builder) {
        this.clock = clock;
        this.integrations = builder.clone()
                .baseUrl(properties.integrationsUrl())
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.addFirst(new MappingJackson2HttpMessageConverter(reader.mapper()));
                })
                .build();
    }

    @Override
    public Optional<OhipConnection> of(String pmsHotelCode) {
        var current = kept.get(pmsHotelCode);
        if (current != null && current.at().plus(KEEP).isAfter(clock.instant())) {
            return current.connection();
        }
        Optional<OhipConnection> fresh;
        try {
            fresh = Optional.ofNullable(integrations.get().uri("/integrations/connections/{hotel}", pmsHotelCode)
                    .retrieve().body(OhipConnection.class));
        } catch (HttpClientErrorException.NotFound e) {
            fresh = Optional.empty();
        }
        kept.put(pmsHotelCode, new Kept(fresh, clock.instant()));
        return fresh;
    }

    @Override
    public Optional<OhipConnection> chain(String pmsHotelCode) {
        var chain = integrations.get().uri("/integrations/chain-connection").retrieve().body(OhipConnection.class);
        if (chain == null || chain.gatewayUrl() == null || chain.gatewayUrl().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new OhipConnection(pmsHotelCode, chain.gatewayUrl(), chain.appKey(), chain.clientId(),
                chain.clientSecret(), chain.enterpriseId()));
    }

    @Override
    public List<IntegrationView> integrations() {
        var list = integrations.get().uri("/integrations/views").retrieve()
                .body(new ParameterizedTypeReference<List<IntegrationView>>() {
                });
        return list == null ? List.of() : list;
    }
}
