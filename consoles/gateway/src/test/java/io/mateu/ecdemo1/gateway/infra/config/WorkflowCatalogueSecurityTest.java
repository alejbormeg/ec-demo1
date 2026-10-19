package io.mateu.ecdemo1.gateway.infra.config;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.WebFilterChainProxy;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowCatalogueSecurityTest {
    private HttpStatus request(String host, String token) {
        var http = ServerHttpSecurity.http();
        http.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtDecoder(value ->
                Mono.just(Jwt.withTokenValue(value).header("alg", "RS256").subject("synthetic-operator")
                        .claim("realm_access", Map.of("roles", List.of(value.equals("operator") ? "ai-admin" : "demo")))
                        .build()))));
        var config = new SecurityConfig("console.ec1.mateu.io", "rw-console.ec1.mateu.io",
                new KeycloakRealmRoleConverter());
        var chain = new WebFilterChainProxy(config.securityWebFilterChain(http));
        var request = MockServerHttpRequest.get("/api/workflow-agents").header("Host", host);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var exchange = MockServerWebExchange.from(request);
        chain.filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete();
        }).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    @Test void anonymousCannotReadTheCatalogueOnEitherControlHost() {
        assertEquals(HttpStatus.UNAUTHORIZED, request("console.ec1.mateu.io", null));
        assertEquals(HttpStatus.UNAUTHORIZED, request("rw-console.ec1.mateu.io", null));
    }

    @Test void signedInDemoUserCannotReadTheControlCatalogue() {
        assertEquals(HttpStatus.FORBIDDEN, request("console.ec1.mateu.io", "demo"));
    }

    @Test void aiAdministratorCanReadTheControlCatalogue() {
        assertEquals(HttpStatus.OK, request("console.ec1.mateu.io", "operator"));
        assertEquals(HttpStatus.OK, request("rw-console.ec1.mateu.io", "operator"));
    }
}
