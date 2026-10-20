package io.mateu.ecdemo1.gateway.infra.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.gateway.GatewayApplication;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP proxy checks with a synthetic upstream and locally decoded test tokens. */
@SpringBootTest(classes = {GatewayApplication.class, WorkflowExecutionGatewayTest.Tokens.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.web-application-type=reactive",
                "spring.cloud.gateway.httpclient.connect-timeout=1000",
                "spring.cloud.gateway.httpclient.response-timeout=2s"})
class WorkflowExecutionGatewayTest {
    private static final AtomicInteger forwarded = new AtomicInteger();
    private static final ObjectMapper json = new ObjectMapper();
    private static final DisposableServer upstream = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> request.receive().aggregate().asString().defaultIfEmpty("")
                    .flatMap(body -> {
                        forwarded.incrementAndGet();
                        try {
                            var payload = json.writeValueAsString(Map.of("path", request.uri(),
                                    "method", request.method().name(), "body", body,
                                    "authorization", request.requestHeaders().get("Authorization", "")));
                            return response.header("Content-Type", "application/json")
                                    .sendString(Mono.just(payload)).then();
                        } catch (JsonProcessingException error) {
                            return Mono.error(error);
                        }
                    })).bindNow();

    @Autowired private WebTestClient client;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry properties) {
        properties.add("ORCHESTRATOR_URL", () -> "http://127.0.0.1:" + upstream.port());
        // A missing API route must fail rather than accidentally pass through a shell.
        properties.add("SHELL_URL", () -> "http://127.0.0.1:1");
        properties.add("SHELL_REDWOOD_URL", () -> "http://127.0.0.1:1");
    }

    @AfterAll static void closeUpstream() { upstream.disposeNow(); }

    @Test void anonymousRequestsNeverReachTheEngine() {
        int before = forwarded.get();
        for (var path : List.of("/workflow/api/test-runs", "/workflow/api/processes/run/graph",
                "/workflow/api/processes/run/cancel")) {
            client.post().uri(path).header("Host", "ec1.mateu.io").exchange()
                    .expectStatus().isUnauthorized();
        }
        assertThat(forwarded.get()).isEqualTo(before);
    }

    @Test void ordinaryUsersAndAiAdministratorsCannotExecuteWorkflows() {
        int before = forwarded.get();
        for (var token : List.of("user", "ai-admin")) {
            client.post().uri("/workflow/api/test-runs").header("Host", "ec1.mateu.io")
                    .headers(headers -> headers.setBearerAuth(token)).exchange()
                    .expectStatus().isForbidden();
        }
        assertThat(forwarded.get()).isEqualTo(before);
    }

    @Test void invalidTokensAndBasicLoginCannotBypassKeycloak() {
        int before = forwarded.get();
        client.get().uri("/workflow/api/processes").header("Host", "ec1.mateu.io")
                .headers(headers -> headers.setBearerAuth("invalid")).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/workflow/api/processes").header("Host", "ec1.mateu.io")
                .headers(headers -> headers.setBasicAuth("synthetic-user", UUID.randomUUID().toString()))
                .exchange().expectStatus().isUnauthorized();
        assertThat(forwarded.get()).isEqualTo(before);
    }

    @Test void workflowAdministratorCanStartThroughEitherDemoHostWithoutRewriting() {
        var input = "{\"runId\":\"synthetic-run\",\"variables\":{\"message\":\"synthetic\"}}";
        for (var host : List.of("ec1.mateu.io", "rw.ec1.mateu.io")) {
            client.post().uri("/workflow/api/test-runs").header("Host", host)
                    .headers(headers -> headers.setBearerAuth("admin"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange()
                    .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody().jsonPath("$.path").isEqualTo("/workflow/api/test-runs")
                    .jsonPath("$.method").isEqualTo("POST").jsonPath("$.body").isEqualTo(input)
                    .jsonPath("$.authorization").isEqualTo("Bearer admin");
        }
    }

    @Test void monitoringPaginationAndCancellationReachTheSameEngine() {
        for (var path : List.of("/workflow/api/processes/run", "/workflow/api/processes/run/graph",
                "/workflow/api/processes/run/children?page=2&size=20&stepId=child",
                "/workflow/api/processes/run/logs", "/workflow/api/processes/run/cancel")) {
            var method = path.endsWith("/cancel") ? HttpMethod.POST : HttpMethod.GET;
            client.method(method).uri(path).header("Host", "ec1.mateu.io")
                    .headers(headers -> headers.setBearerAuth("admin")).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.path").isEqualTo(path)
                    .jsonPath("$.method").isEqualTo(method.name());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Tokens {
        @Bean ReactiveJwtDecoder testTokenDecoder() {
            return token -> {
                if (token.equals("invalid")) return Mono.error(new BadJwtException("Invalid synthetic token"));
                return Mono.just(Jwt.withTokenValue(token).header("alg", "RS256")
                        .subject("synthetic-operator").issuedAt(Instant.now().minusSeconds(60))
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claim("realm_access", Map.of("roles", List.of(token))).build());
            };
        }
    }
}
