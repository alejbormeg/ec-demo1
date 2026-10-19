package io.mateu.ecdemo1.iacp.infra.in.rest;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowJwtAuthenticationFilterTest {
    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("https://identity.synthetic/realms/test")
                .audience("workflow-agent-executor").subject("technical-account")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("azp", "workflow-agent-executor")
                .claim("realm_access", Map.of("roles", List.of("workflow-agent-executor")));
    }
    @Test void validatesIssuerAudienceExpiryClientAndRole() throws Exception {
        assertDoesNotThrow(() -> validate(claims().build()));
        assertThrows(Exception.class, () -> validate(claims().issuer("https://wrong").build()));
        assertThrows(Exception.class, () -> validate(claims().audience("account").build()));
        assertThrows(Exception.class, () -> validate(claims().expirationTime(Date.from(Instant.now().minusSeconds(1))).build()));
        assertThrows(Exception.class, () -> validate(claims().claim("azp", "console").build()));
        assertThrows(Exception.class, () -> validate(claims().claim("realm_access", Map.of("roles", List.of("admin"))).build()));
    }
    static void validate(JWTClaimsSet c) throws Exception {
        WorkflowJwtAuthenticationFilter.validate(c, "https://identity.synthetic/realms/test", "workflow-agent-executor", "workflow-agent-executor");
    }
    @Test void checksRealSignaturesAndDoesNotTrustDecodedClaims() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        var filter = new WorkflowJwtAuthenticationFilter("https://identity.synthetic/realms/test",
                "workflow-agent-executor", "workflow-agent-executor", new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));
        var good = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims().build());
        good.sign(new RSASSASigner(key));
        var accepted = call(filter, good.serialize()); assertEquals(200, accepted.getStatus());
        var wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        var forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims().build());
        forged.sign(new RSASSASigner(wrongKey));
        assertEquals(401, call(filter, forged.serialize()).getStatus());
        assertEquals(401, call(filter, new PlainJWT(claims().build()).serialize()).getStatus());
    }
    static MockHttpServletResponse call(WorkflowJwtAuthenticationFilter filter, String token) throws Exception {
        var request = new MockHttpServletRequest(); request.setServletPath("/internal/workflow-agents");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            called.set(true); assertEquals("technical-account", req.getAttribute(WorkflowJwtAuthenticationFilter.ACTOR_ATTRIBUTE));
        });
        if (response.getStatus() == 200) assertTrue(called.get()); else assertFalse(called.get());
        return response;
    }
    @Test void missingSecurityConfigurationFailsClosedAndExistingChatRemainsUntouched() throws Exception {
        var filter = new WorkflowJwtAuthenticationFilter("", "", "workflow-agent-executor", "workflow-agent-executor");
        assertEquals(401, call(filter, "invalid").getStatus());
        var request = new MockHttpServletRequest(); request.setServletPath("/internal/agents/console-agent/config");
        var called = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertTrue(called.get());
    }
    @Test void everyNewWorkflowEndpointIncludingMatrixPathsFailsClosed() throws Exception {
        var filter = new WorkflowJwtAuthenticationFilter("", "", "workflow-agent-executor", "workflow-agent-executor");
        for (var path : List.of("/internal/workflow-agents", "/internal/workflow-agents;ignored",
                "/internal/workflow-agent-executions/run/snapshot", "/internal/workflow-agent-executions/resolve",
                "/internal/workflow-agent-executions/authorize", "/internal/workflow-agent-executions/run/usage",
                "/internal/workflow-agent-executions/run/rag/source/search", "/internal/workflow-agent-executions;ignored/resolve")) {
            var request = new MockHttpServletRequest(); request.setServletPath(path);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Workflow endpoint must require authentication"));
            assertEquals(401, response.getStatus(), path);
        }
    }

}
