package io.mateu.ecdemo1.iaagent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.config.AgentConfigClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Every agent in the catalogue, over A2A, from any pod of this service: {@code /a2a/{agentId}}.
 * The protocol is {@link A2aServer}'s; this only puts it on HTTP.
 *
 * <p><strong>Not for the internet.</strong> The gateway routes {@code /ai/**} here and nothing
 * else, and the front-office host refuses {@code /a2a/**} outright, so these are reachable from
 * inside the cluster only — by another agent, which is who they are for. A {@code message/send}
 * still needs a bearer token, the caller's, forwarded by the calling agent: it is who the answering
 * agent acts for, which MCP servers see and which budgets are charged. The token is read, not
 * verified, exactly as on the chat endpoints — the gateway verified it on the way in to the first
 * agent (see {@code JwtIdentityReader}).
 *
 * <p>Bodies are read and written as strings through this service's Jackson 2 mapper: HTTP
 * conversion here is Jackson 3's, and the protocol classes are written against 2.
 */
@RestController
@RequestMapping("/a2a/{agentId}")
@org.springframework.context.annotation.Profile("!workflow")
public class A2aController {

    private static final Logger log = LoggerFactory.getLogger(A2aController.class);

    private final A2aServer server;
    private final boolean requireToken;
    private final ObjectMapper mapper = new ObjectMapper();

    public A2aController(AgentConfigClient configClient, A2aAgentExecutor executor,
                         @Value("${ia.a2a.max-depth:2}") int maxDepth,
                         @Value("${ia.a2a.base-url:http://ia-agent:8095}") String baseUrl,
                         @Value("${ia.a2a.require-token:true}") boolean requireToken) {
        this.server = new A2aServer(configClient::configOf, executor, maxDepth, baseUrl);
        this.requireToken = requireToken;
    }

    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> agentCard(@PathVariable("agentId") String agentId) throws Exception {
        var card = server.agentCard(agentId);
        if (card == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("error", "No servable agent '" + agentId + "'")));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(card));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jsonRpc(@PathVariable("agentId") String agentId,
                                          @RequestBody String body,
                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = A2aHop.DEPTH_HEADER, required = false) String depth,
                                          @RequestHeader(value = A2aHop.CHAIN_HEADER, required = false) String chain,
                                          @RequestHeader(value = A2aHop.NO_DELEGATION_HEADER, required = false)
                                          String noDelegation) throws Exception {
        if (requireToken && (authorization == null || !authorization.startsWith("Bearer "))) {
            log.warn("A2A call to {} refused: no bearer token", agentId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("error", "A bearer token is required")));
        }
        var hop = A2aHop.fromHeaders(depth, chain, noDelegation);
        var response = server.handle(agentId, body, authorization, hop);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(response));
    }
}
