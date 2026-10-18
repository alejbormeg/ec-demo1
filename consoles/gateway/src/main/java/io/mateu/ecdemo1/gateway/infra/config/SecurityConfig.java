package io.mateu.ecdemo1.gateway.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What this gateway is for, beyond routing.
 *
 * <p>The orchestrator, the forms engine and the worker only speak HTTP basic auth — none of them
 * understands OIDC — so behind a plain ingress their management UIs would be reachable by anyone
 * who typed the path, and those UIs can pause definitions and cancel processes. Validating the
 * realm's access token here is what closes that, without asking three applications to grow an
 * identity integration. The booking, content and users services have no security of their own at
 * all, and the chat agent spends money per request, so the same applies to them with less margin.
 *
 * <p>Two kinds of caller reach those paths and both carry the token:
 * <ul>
 *   <li>the browser, whose Mateu client puts {@code Authorization: Bearer} on every XHR through a
 *       single axios interceptor, whatever base URL it is calling;
 *   <li>the shell's own pod, which resolves each {@code RemoteMenu} server-side and propagates the
 *       caller's Authorization header when it does.
 * </ul>
 *
 * <p>The one Mateu path that could not carry a token is {@code /mateu/v3/sse/...}: the SSE client
 * is a bare {@code fetch} that sets only Accept and Content-Type, outside the axios interceptor. It
 * is not exempted here because nothing in these UIs uses it — {@code @Action(sse)} defaults to
 * false and none of them sets it. If a future screen does, it will 401 through this gateway, and
 * the fix belongs in Mateu's SSE client rather than in a hole punched here. The chat panel's own
 * stream is a different client and not affected: it sets the Authorization header itself.
 *
 * <p>Public on purpose: the shell itself and everything it serves, because the bootstrap page is
 * what redirects an anonymous visitor to Keycloak — requiring a token to fetch it would mean nobody
 * could ever obtain one; {@code /eventconductor/**}, the workflow-graph web component, which the
 * browser loads as a script tag and script tags send no headers; and the two git webhook receivers,
 * which GitHub calls with no token at all and which the engine authenticates by HMAC over the body.
 * That last one only holds while a webhook secret is configured — a blank secret makes the engine
 * verify nothing, and then these are two open endpoints that re-clone a repository on demand.
 *
 * <h2>The control console</h2>
 *
 * <p>The second host is not another prefix, it is another audience. Everything behind
 * {@code console.ec1.mateu.io} decides what the chat agent is and what it spends — its model, its
 * system prompt, the API key it authenticates with — so a login is not enough there: the
 * {@code ai-admin} realm role is required, and this is the only place that requirement is written.
 * The control shell and the control plane behind it authenticate nothing of their own, exactly
 * like the demo console's backends.
 *
 * <p><strong>{@code ai-admin} and not {@code admin}, and the distinction is the point.</strong>
 * The demo console's {@code admin} drives workflow definitions and processes; it never reaches an
 * LLM credential. This host does, and nothing else. Keeping them as separate realm roles is what
 * lets an operator hold one without the other — the person who rotates the Anthropic key need not
 * be able to cancel a process, and the person who does need not be able to read the key. A single
 * {@code admin} covering both would make every platform admin an AI admin by accident, which is
 * the failure this split exists to prevent.
 *
 * <p>The rule is scoped by host as well as by path because both consoles serve {@code /mateu/**}
 * and both have a catch-all. Scoping it by path alone would either lock the demo console's own
 * shell behind the admin role, or leave this one open — and the second failure is silent.
 *
 * <p>What stays public on the control host is its bootstrap page and its static assets, for the
 * same reason as the demo console's: that page is what sends an anonymous visitor to Keycloak, so
 * requiring a token to fetch it would mean nobody could ever obtain one.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * The control console's hosts, as a browser asks for them. They have to match what the
     * gateway's routes use, which is why both read the same properties.
     *
     * <p>There are two of them because the control console is served by two shells — the Vaadin
     * one and the Redwood one — and they are the same console: same backends, same catalogues,
     * same credentials behind them. A host missing from this set does not fail closed; its
     * /_ia-cp and /_users requests fall through to the catch-all rule at the bottom, which permits
     * everything. So the set is the security boundary, and adding a console host without adding it
     * here publishes that console.
     */
    private final Set<String> controlHosts;
    private final KeycloakRealmRoleConverter roleConverter;

    public SecurityConfig(@Value("${CONTROL_HOST:console.ec1.mateu.io}") String controlHost,
                          @Value("${RW_CONTROL_HOST:rw-console.ec1.mateu.io}") String redwoodControlHost,
                          KeycloakRealmRoleConverter roleConverter) {
        this.controlHosts = Stream.of(controlHost, redwoodControlHost)
                .filter(host -> host != null && !host.isBlank())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.roleConverter = roleConverter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // The gateway holds no session and serves no forms of its own; every caller
                // authenticates with a bearer token or not at all.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**").permitAll()
                        // Metrics, for Prometheus and nobody else: it scrapes the pod directly
                        // (the prometheus.io/* annotations), so its request never crossed the
                        // ingress — see fromInsideCluster.
                        .matchers(fromInsideCluster("/actuator/prometheus")).permitAll()
                        // Every other actuator path, on every host. Not only the gateway's own:
                        // a path the gateway does not expose falls through to the host's
                        // catch-all route, and the front office's or a shell's actuator would be
                        // published that way.
                        .pathMatchers("/actuator", "/actuator/**").denyAll()
                        .pathMatchers("/eventconductor/**").permitAll()
                        // Git webhooks, listed explicitly rather than left to the catch-all
                        // below, because "public" is a decision here and not an oversight:
                        // GitHub cannot hold a Keycloak token, so these authenticate by
                        // HMAC-SHA256 over the body, verified by the engine itself.
                        .pathMatchers("/workflow/webhooks/**", "/forms/webhooks/**").permitAll()
                        .pathMatchers("/_workflow/**", "/_forms/**").authenticated()
                        // Web Push's two scripts, public: a browser loads a <script> and registers a
                        // service worker without a token. Code, no data — the endpoints they call
                        // (the key, the subscriptions) stay behind the rule below.
                        .pathMatchers("/_inbox/push/push.js", "/_inbox/push/sw.js").permitAll()
                        // The inbox, on every host: what it shows depends on the caller's roles.
                        // Except the service's own path typed in the address bar: a GET with no
                        // token, which the route inbox-page only redirects to the shell's route.
                        .pathMatchers(HttpMethod.GET, "/_inbox", "/_inbox/").permitAll()
                        .pathMatchers("/_inbox/**").authenticated()
                        // The external APIs' usage (the page behind the home pages' tiles), on every host
                        // like the inbox: counts, no personal data, but not for anonymous callers.
                        // Only these screens: the services' /usage endpoints have no route at all.
                        // Except the page's own path typed in the address bar: a GET with no token,
                        // which the route api-usage-page only redirects to the shell's route for it.
                        .pathMatchers(HttpMethod.GET, "/_api-usage/apis").permitAll()
                        .pathMatchers("/_api-usage/**").authenticated()
                        // The demo's banner (a simulated Opera outage), on every host: signed in, nothing else.
                        .pathMatchers("/_demo-banner/**").authenticated()
                        // The two demo CRUD services, guarded the same way and for the same
                        // reason: none of them authenticates anything of its own, so this is
                        // the only thing between their screens and whoever types the path. Users
                        // used to be here too; it moved to the control host, behind ai-admin — see
                        // below — because administering access is a control-plane concern.
                        .pathMatchers("/_booking/**", "/_content/**").authenticated()
                        // The integration PoC's product-side screen: the partners master. Same
                        // reason as the two above.
                        .pathMatchers("/_erp/**").authenticated()
                        // Clientes, the MDM's business face on the data plane: personal data, so never
                        // anonymous — read-only, and a signed-in user of the data plane is who it is for.
                        .pathMatchers("/_customers/**").authenticated()
                        // A booking's journey (journey-service): who the guests are and where the
                        // reservation went — personal data again, so signed in like Clientes.
                        .pathMatchers("/_journey/**").authenticated()
                        // Avisos (the notices service): what the desk must know of a guest, a
                        // reservation or an agency — signed in, and written as who did it.
                        .pathMatchers("/_notices/**").authenticated()
                        // Historial de clientes: where a customer stayed and what they spent —
                        // personal data, so signed in like Clientes.
                        .pathMatchers("/_history/**").authenticated()
                        // Riu Class: a member's tier and points — personal data, signed in.
                        .pathMatchers("/_loyalty/**").authenticated()
                        // The chat agent. Every prompt costs Anthropic tokens against this
                        // deployment's key, so leaving it open is not a UI question, it is a
                        // bill. It can be required because Mateu's chat client does send the
                        // bearer token — it reads it from localStorage and sets the header
                        // itself, unlike the @Action(sse) client described above.
                        // The control console's chat, behind ai-admin like everything else on that
                        // host: its agent reaches the integrations, the mapping approvals and the
                        // customer MDM — the same decisions and personal data as the screens.
                        // Before the rule below, which would otherwise decide it as `authenticated`.
                        .matchers(onControlHost("/ai/**")).hasRole("ai-admin")
                        .pathMatchers("/ai/**").authenticated()
                        // The GitOps webhook, public on the control host and verified by HMAC in
                        // the control plane — GitHub cannot carry an ai-admin token. Listed before
                        // the rule below so it is decided here and cannot be swept under it; the
                        // catch-all would permit it anyway, but silence is the wrong way to make a
                        // public endpoint public.
                        .matchers(onControlHost("/cp-webhooks/**")).permitAll()
                        // The control console — an admin console whose halves are the IA
                        // catalogues, user and access management, and the two engines'
                        // administration UIs. /_workflow-admin and /_forms-admin are served by the
                        // same orchestrator and forms pods that answer /_workflow and /_forms on
                        // the demo host, so this is the only thing standing between a workflow
                        // definition and a user who holds nothing but the demo role. It is also
                        // why they are separate path segments rather than a suffix: the
                        // /_workflow/** rule further up matches on any host, and a path it caught
                        // would be decided as `authenticated` before this line was ever reached.
                        .matchers(onControlHost("/_ia-cp/**", "/_users/**", "/mateu/**",
                                "/_workflow-admin/**", "/_forms-admin/**",
                                // The integration's control plane (PoC ACL): activating a hotel or
                                // approving a mapping decides what reaches the PMS.
                                "/_integrations/**", "/_mapping/**", "/_communication/**",
                                // The customer MDM: golden records are personal data.
                                "/_mdm/**",
                                // The registration rules: what the law requires of every guest.
                                "/_registration-rules/**",
                                // The audit trail: who did what is personal data too.
                                "/_audit/**",
                                // The demo's administration: resetting it to zero deletes data,
                                // Salesforce's too; simulating an Opera outage stops the PMS.
                                "/_demo/**")).hasRole("ai-admin")
                        .anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Without this, a realm admin's token arrives with no authorities and
                        // hasRole denies everything — see KeycloakRealmRoleConverter.
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter)))
                .build();
    }

    /**
     * Matches a path on the control host and nowhere else.
     *
     * <p>The Host header rather than the URI's host: behind ingress-nginx the request line the
     * gateway sees is not the one the browser sent, and the header is what survives the hop. The
     * port is stripped because a browser sends {@code host:443} only sometimes, and a rule that
     * depends on which is a rule that fails in one environment and not the other.
     */
    private ServerWebExchangeMatcher onControlHost(String... patterns) {
        var paths = new OrServerWebExchangeMatcher(Arrays.stream(patterns)
                .map(p -> (ServerWebExchangeMatcher) new PathPatternParserServerWebExchangeMatcher(p))
                .toList());
        return new AndServerWebExchangeMatcher(this::isControlHost, paths);
    }

    /**
     * Matches a path when the request did not come through the ingress.
     *
     * <p>ingress-nginx sets {@code X-Forwarded-For} and {@code X-Real-IP} on every request it
     * proxies, overwriting or appending to whatever the client sent, so a caller on the internet
     * cannot make them disappear. A request without either reached the pod from inside the
     * cluster — Prometheus scraping the pod IP. Nothing in this application strips them first:
     * no forward-headers-strategy is set, so on Kubernetes Spring Boot uses the native one, which
     * reads them and leaves them on the request. Setting it to {@code framework} would remove them
     * before this runs and open metrics to the internet.
     */
    private ServerWebExchangeMatcher fromInsideCluster(String pattern) {
        ServerWebExchangeMatcher notForwarded = exchange -> {
            var headers = exchange.getRequest().getHeaders();
            return headers.getFirst("X-Forwarded-For") != null || headers.getFirst("X-Real-IP") != null
                    || headers.getFirst("Forwarded") != null
                    ? ServerWebExchangeMatcher.MatchResult.notMatch()
                    : ServerWebExchangeMatcher.MatchResult.match();
        };
        return new AndServerWebExchangeMatcher(notForwarded,
                new PathPatternParserServerWebExchangeMatcher(pattern));
    }

    private Mono<ServerWebExchangeMatcher.MatchResult> isControlHost(ServerWebExchange exchange) {
        var host = exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        if (host == null) {
            return ServerWebExchangeMatcher.MatchResult.notMatch();
        }
        var withoutPort = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return controlHosts.contains(withoutPort.toLowerCase(Locale.ROOT))
                ? ServerWebExchangeMatcher.MatchResult.match()
                : ServerWebExchangeMatcher.MatchResult.notMatch();
    }
}
