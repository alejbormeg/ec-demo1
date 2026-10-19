package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Validates signed Keycloak JWTs; workflow identity is never obtained from user-supplied metadata. */
public final class WorkflowIdentity {
    private final ConfigurableJWTProcessor<SecurityContext> jwt=new DefaultJWTProcessor<>();
    private final String issuer,audience,clientId,clientSecret,tokenUri;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper; private String cached; private long expires;
    public WorkflowIdentity(String issuer,String jwks,String audience,String clientId,String clientSecret,String tokenUri,ObjectMapper mapper) {
        this.issuer=issuer;this.audience=audience;this.clientId=clientId;this.clientSecret=clientSecret;this.tokenUri=tokenUri;this.mapper=mapper;
        try {jwt.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,new RemoteJWKSet<>(URI.create(jwks).toURL())));}
        catch(Exception ex){throw new IllegalArgumentException("Invalid workflow JWKS URI",ex);}
    }
    public String authenticate(String authorization,boolean operator) {
        try {
            if(authorization==null||!authorization.startsWith("Bearer "))throw new SecurityException("Bearer token required");
            var claims=jwt.process(authorization.substring(7),null);var now=new Date();
            var subject=claims.getSubject();
            if(subject==null||subject.isBlank())throw new SecurityException("JWT subject is required");
            if(!issuer.equals(claims.getIssuer())||claims.getExpirationTime()==null||!now.before(claims.getExpirationTime())
                    ||(claims.getNotBeforeTime()!=null&&now.before(claims.getNotBeforeTime()))||!(claims.getAudience().contains(operator?"account":audience)))
                throw new SecurityException("JWT issuer, audience or validity invalid");
            var realm=claims.getJSONObjectClaim("realm_access");var roles=realm==null?List.of():realm.get("roles");
            var required=operator?"ai-admin":"workflow-agent-executor";
            if(!(roles instanceof List<?> list)||!list.contains(required))throw new SecurityException("Missing "+required+" role");
            if(!operator && !clientId.equals(claims.getStringClaim("azp")))throw new SecurityException("Workflow service account required");
            return subject;
        }catch(SecurityException ex){throw ex;}catch(Exception ex){throw new SecurityException("Invalid workflow bearer token",ex);}
    }
    public synchronized String authorization() {
        if(cached!=null&&System.currentTimeMillis()<expires)return "Bearer "+cached;
        try {
            var form="grant_type=client_credentials&client_id="+URLEncoder.encode(clientId,java.nio.charset.StandardCharsets.UTF_8)+"&client_secret="+URLEncoder.encode(clientSecret,java.nio.charset.StandardCharsets.UTF_8);
            var res=http.send(HttpRequest.newBuilder(URI.create(tokenUri)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.ofString());
            if(res.statusCode()!=200)throw new SecurityException("Service account token request failed: "+res.statusCode());
            var json=mapper.readTree(res.body());cached=json.path("access_token").asText();
            authenticate("Bearer "+cached,false);expires=System.currentTimeMillis()+Math.max(1,json.path("expires_in").asLong(30)-15)*1000;
            return "Bearer "+cached;
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new SecurityException("Cannot obtain workflow identity",ex);}
    }
}
