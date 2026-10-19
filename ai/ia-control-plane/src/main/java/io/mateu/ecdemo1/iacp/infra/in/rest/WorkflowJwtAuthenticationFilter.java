package io.mateu.ecdemo1.iacp.infra.in.rest;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Scope authentication to the new workflow API; existing console/chat routes keep their contract. */
@Component
public class WorkflowJwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String ACTOR_ATTRIBUTE = WorkflowJwtAuthenticationFilter.class.getName() + ".actor";
    private final DefaultJWTProcessor<SecurityContext> processor;
    private final String issuer;
    private final String audience;
    private final String clientId;
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowJwtAuthenticationFilter(
            @Value("${cp.workflow.security.issuer:}") String issuer,
            @Value("${cp.workflow.security.jwk-set-uri:}") String jwkSetUri,
            @Value("${cp.workflow.security.audience:workflow-agent-executor}") String audience,
            @Value("${cp.workflow.security.client-id:workflow-agent-executor}") String clientId) {
        this(issuer, audience, clientId, remoteKeys(issuer, jwkSetUri));
    }
    WorkflowJwtAuthenticationFilter(String issuer, String audience, String clientId, JWKSource<SecurityContext> keys) {
        this.issuer = issuer; this.audience = audience; this.clientId = clientId;
        if (keys == null) { processor = null; return; }
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    }
    private static JWKSource<SecurityContext> remoteKeys(String issuer, String jwkSetUri) {
        if (issuer.isBlank() || jwkSetUri.isBlank()) return null;
        try { return new RemoteJWKSet<SecurityContext>(URI.create(jwkSetUri).toURL()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid workflow JWT configuration"); }
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getServletPath();
        return !(path.startsWith("/internal/workflow-agents") || path.startsWith("/internal/workflow-agent-executions"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        var authorization = request.getHeader("Authorization");
        if (processor == null || authorization == null || !authorization.startsWith("Bearer ")) {
            response.setStatus(401); return;
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(authorization.substring(7), null);
            validate(claims, issuer, audience, clientId);
        } catch (Exception e) { response.setStatus(401); return; }
        request.setAttribute(ACTOR_ATTRIBUTE, claims.getSubject());
        chain.doFilter(request, response);
    }
    /** Explicit issuer/audience/identity/expiry checks in addition to RS256 signature validation. */
    static void validate(JWTClaimsSet claims, String issuer, String audience, String clientId) throws Exception {
        var now = Instant.now();
        if (!issuer.equals(claims.getIssuer()) || claims.getExpirationTime() == null
                || !claims.getExpirationTime().toInstant().isAfter(now)
                || claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(30))
                || claims.getSubject() == null || claims.getSubject().isBlank()
                || !claims.getAudience().contains(audience)
                || !clientId.equals(claims.getStringClaim("azp")))
            throw new IllegalArgumentException("Invalid workflow service token");
        var access = claims.getJSONObjectClaim("realm_access");
        if (access == null || !(access.get("roles") instanceof List<?> roles)
                || !roles.contains("workflow-agent-executor"))
            throw new IllegalArgumentException("Workflow executor role required");
    }
}
