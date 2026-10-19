package io.mateu.ecdemo1.iaagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.a2a.A2aHop;
import io.mateu.ecdemo1.iaagent.a2a.GuardrailRunner;
import io.mateu.ecdemo1.iaagent.a2a.PeerToolFactory;
import io.mateu.ecdemo1.iaagent.config.AgentConfig;
import io.mateu.ecdemo1.iaagent.config.AgentConfigClient;
import io.mateu.ecdemo1.iaagent.config.AgentResolver;
import io.mateu.ecdemo1.iaagent.config.ChatClientRegistry;
import io.mateu.ecdemo1.iaagent.config.RagToolFactory;
import io.mateu.ecdemo1.iaagent.observability.PromptObservations;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/api/agent")
@org.springframework.context.annotation.Profile("!workflow")
public class IaAgentController {

    private static final Logger log = LoggerFactory.getLogger(IaAgentController.class);


    private final AgentConfigClient configClient;
    private final ChatClientRegistry chatClients;
    private final PerRequestMcpClientFactory mcpFactory;
    private final RagToolFactory ragTools;
    private final ConversationStore conversationStore;
    private final MenuContextStore menuContextStore;
    private final ObjectMapper objectMapper;
    private final io.mateu.ecdemo1.iaagent.identity.JwtIdentityReader jwtIdentityReader;
    private final io.mateu.ecdemo1.iaagent.usage.UsageReporter usageReporter;
    private final AgentResolver agentResolver;
    private final ObservationRegistry observationRegistry;
    private final PromptObservations prompts;
    private final AgentTurn agentTurn;
    private final PeerToolFactory peerTools;
    private final GuardrailRunner guardrails;

    public IaAgentController(AgentConfigClient configClient,
                             ChatClientRegistry chatClients,
                             PerRequestMcpClientFactory mcpFactory,
                             RagToolFactory ragTools,
                             ConversationStore conversationStore,
                             MenuContextStore menuContextStore,
                             ObjectMapper objectMapper,
                             io.mateu.ecdemo1.iaagent.identity.JwtIdentityReader jwtIdentityReader,
                             io.mateu.ecdemo1.iaagent.usage.UsageReporter usageReporter,
                             AgentResolver agentResolver,
                             ObservationRegistry observationRegistry,
                             PromptObservations prompts,
                             AgentTurn agentTurn,
                             PeerToolFactory peerTools,
                             GuardrailRunner guardrails) {
        this.configClient = configClient;
        this.chatClients = chatClients;
        this.mcpFactory = mcpFactory;
        this.ragTools = ragTools;
        this.conversationStore = conversationStore;
        this.menuContextStore = menuContextStore;
        this.objectMapper = objectMapper;
        this.jwtIdentityReader = jwtIdentityReader;
        this.usageReporter = usageReporter;
        this.agentResolver = agentResolver;
        this.observationRegistry = observationRegistry;
        this.prompts = prompts;
        this.agentTurn = agentTurn;
        this.peerTools = peerTools;
        this.guardrails = guardrails;
    }

    // ── Observation ──────────────────────────────────────────────────────────

    /** See {@link PromptObservations}: shared with the A2A endpoint. */
    private Observation startPromptObservation(Observation parent, String sessionId, String userMessage) {
        return prompts.start(parent, sessionId, userMessage, A2aHop.origin().depth());
    }

    private void tagResponse(Observation observation, String response) {
        prompts.tagResponse(observation, response);
    }

    private static void tagAgent(Observation observation, AgentConfig config) {
        PromptObservations.tagAgent(observation, config);
    }

    private static void tagTools(Observation observation, PerRequestMcpClientFactory.PerRequestTools tools,
                                 int toolCount) {
        PromptObservations.tagTools(observation, toolCount, tools.expectedServers(), tools.connectedServers());
    }

    private static void outcome(Observation observation, String outcome) {
        PromptObservations.outcome(observation, outcome);
    }

    /**
     * Raised when this pod has no configuration to answer with at all — the control plane has
     * never been reachable, or it refuses to serve this agent. Readiness reports the same thing,
     * so a prompt should not normally get this far; when one does, the message is the diagnosis.
     */
    private static class NoConfigurationException extends RuntimeException {
        NoConfigurationException(String message) { super(message); }
    }

    /**
     * Every tool this agent has, in one list: the MCP servers', the RAG sources' and one per peer
     * agent it may call over A2A. The model sees one list, and nothing downstream has to know which
     * kind a call belongs to.
     */
    private List<org.springframework.ai.tool.ToolCallback> allTools(
            AgentConfig config, PerRequestMcpClientFactory.PerRequestTools mcp,
            List<org.springframework.ai.tool.ToolCallback> peers) {
        return allTools(config, mcp, peers, ToolProgressListener.NONE);
    }

    /** As above, with the RAG searches reported to {@code progress} (the other two kinds already are). */
    private List<org.springframework.ai.tool.ToolCallback> allTools(
            AgentConfig config, PerRequestMcpClientFactory.PerRequestTools mcp,
            List<org.springframework.ai.tool.ToolCallback> peers, ToolProgressListener progress) {
        var all = new ArrayList<org.springframework.ai.tool.ToolCallback>(
                List.of(mcp.getCallbacks()));
        all.addAll(ragTools.toolsFor(config.rags(), progress));
        all.addAll(peers);
        return all;
    }

    /**
     * The configuration for this prompt, chosen by context. The caller's identity is read from the
     * bearer token and posted to the control plane, which routes to an agent and refuses an
     * over-budget one — a refusal arrives here as {@link NoConfigurationException} and reaches the
     * panel as its message, the same path a missing configuration already took.
     *
     * <p>Locale and the current screen come from the request when the chat client sends them, and
     * are null when it does not — so routing rules that key on a role or a tenant work regardless,
     * and rules that key on a screen or a locale start working the moment the frontend carries
     * those fields, with no change here.
     */
    private AgentConfig resolveConfig(String authorization, ChatRequest request, Console console) {
        var caller = jwtIdentityReader.read(authorization);
        var resolution = agentResolver.resolve(caller, request.locale(), request.currentRoute(),
                console.channel(), console.defaultAgent());
        if (!resolution.allowed()) {
            throw new NoConfigurationException(resolution.deniedReason());
        }
        return resolution.config();
    }

    // ── Guardrails ───────────────────────────────────────────────────────────

    /**
     * The route's input guardrails, over A2A, before the agent sees anything: the text to answer
     * — the user's, or a guardrail's rewrite of it — or a refusal, recorded on the observation.
     */
    private GuardrailRunner.Outcome checkInput(Observation observation, AgentConfig config, String message,
                                               String authorization) {
        var outcome = guardrails.check(GuardrailRunner.Side.INPUT, config, message, authorization, A2aHop.origin());
        if (outcome.blocked()) {
            var refusal = GuardrailRunner.refusal(GuardrailRunner.Side.INPUT, outcome);
            tagResponse(observation, refusal);
            outcome(observation, "blocked_input");
        }
        return outcome;
    }

    /**
     * The route's output guardrails on the whole answer, before any of it leaves: what the user
     * gets. This is why an answer is never sent in pieces while a route has output guardrails — a
     * token already on the wire cannot be taken back. So /stream sends no deltas for such a route —
     * only its status and tool events — and the answer in one event, after this.
     *
     * <p>They read the answer as the user would, without navigation markers; one they allow
     * unchanged keeps its markers, one they rewrite loses them.
     */
    private Checked checkOutput(AgentConfig config, String raw, String authorization) {
        if (config.guardrailsOrNone().output().isEmpty()) {
            return new Checked(raw, false);
        }
        var clean = parseNavigation(raw).cleanText();
        var outcome = guardrails.check(GuardrailRunner.Side.OUTPUT, config, clean, authorization, A2aHop.origin());
        if (outcome.blocked()) {
            return new Checked(GuardrailRunner.refusal(GuardrailRunner.Side.OUTPUT, outcome), true);
        }
        return new Checked(outcome.text().equals(clean) ? raw : outcome.text(), false);
    }

    /** The answer the user gets, and whether it is a refusal in place of the agent's. */
    private record Checked(String text, boolean blocked) {
        String outcome() {
            return blocked ? "blocked_output" : "success";
        }
    }

    // ── Internal types ───────────────────────────────────────────────────────

    private record LlmResult(String content, int inputTokens, int outputTokens, int totalTokens) {}

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildSystemPrompt(String basePrompt, String serverContext, String sessionId,
                                     String screenRoute,
                                     List<org.springframework.ai.tool.ToolCallback> peers) {
        var sb = new StringBuilder(basePrompt);
        if (serverContext != null && !serverContext.isBlank()) {
            sb.append("\n\nContexto de las herramientas disponibles:\n\n").append(serverContext);
        }
        var peerContext = PeerToolFactory.systemContext(peers);
        if (!peerContext.isBlank()) {
            sb.append("\n\n").append(peerContext);
        }
        String menuPrompt = menuContextStore.buildMenuSystemPrompt(sessionId, screenRoute);
        if (!menuPrompt.isBlank()) {
            sb.append("\n\n").append(menuPrompt);
        }
        return sb.toString();
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private ServerSentEvent<String> tokenEvent(int input, int output, int total) {
        return ServerSentEvent.<String>builder()
                .data("{\"inputTokens\":" + input
                        + ",\"outputTokens\":" + output
                        + ",\"totalTokens\":" + total + "}")
                .build();
    }

    private ServerSentEvent<String> contentEvent(String text) {
        return ServerSentEvent.<String>builder().data(text).build();
    }

    private ServerSentEvent<String> errorEvent(Throwable e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        try {
            String json = objectMapper.writeValueAsString(
                    java.util.Map.of("event", "agent-error", "detail", java.util.Map.of("message", message)));
            return ServerSentEvent.<String>builder().data(json).build();
        } catch (Exception ex) {
            return ServerSentEvent.<String>builder()
                    .data("{\"event\":\"agent-error\",\"detail\":{\"message\":\"Error interno\"}}")
                    .build();
        }
    }

    /**
     * Scans {@code rawText} for [NAVIGATE:{...}] markers: the one that is a command becomes the SSE
     * navigation event, the ones the model wrote as links become markdown links (see
     * {@link NavigationMarkers}).
     */
    record ParsedResponse(String cleanText, List<ServerSentEvent<String>> navEvents) {}

    ParsedResponse parseNavigation(String rawText) {
        var parsed = NavigationMarkers.parse(rawText, objectMapper);
        var navEvents = new ArrayList<ServerSentEvent<String>>();
        for (String json : parsed.navigations()) {
            String ssePayload = "{\"event\":\"navigation-requested\",\"detail\":" + json + "}";
            navEvents.add(ServerSentEvent.<String>builder().data(ssePayload).build());
            log.debug("Navigation requested: {}", json);
        }
        return new ParsedResponse(parsed.cleanText(), navEvents);
    }

    /**
     * Which console the prompt came from and the agent that answers there unless a route says
     * otherwise — stamped by the gateway from the host (X-Agent-Channel, X-Default-Agent), after it
     * dropped whatever the browser sent. A caller inside the cluster may send them itself, or not:
     * with neither, the control plane's routes and its own default decide.
     */
    public record Console(String channel, String defaultAgent) {
    }

    public static final String CHANNEL_HEADER = "X-Agent-Channel";
    public static final String DEFAULT_AGENT_HEADER = "X-Default-Agent";

    // ── /chat  (POST, non-streaming) ─────────────────────────────────────────

    /** As {@link #chat(ChatRequest, String, String, String)}, from no console in particular. */
    public String chat(ChatRequest request, String authorization) {
        return chat(request, authorization, null, null);
    }

    @PostMapping(value = "/chat", produces = "text/plain;charset=UTF-8")
    public String chat(@RequestBody ChatRequest request,
                       @RequestHeader(value = "Authorization", required = false) String authorization,
                       @RequestHeader(value = CHANNEL_HEADER, required = false) String channel,
                       @RequestHeader(value = DEFAULT_AGENT_HEADER, required = false) String defaultAgent) {
        var console = new Console(channel, defaultAgent);
        String sessionId = request.sessionId();
        // What happened goes to the log; what was said goes to the trace, and only when
        // IA_CAPTURE_CONTENT says so. The text itself is here at DEBUG, for a local run.
        log.info("Chat request session={}: {} chars", sessionId, length(request.message()));
        log.debug("Chat request session={}: '{}'", sessionId, request.message());
        menuContextStore.update(sessionId, request.menuContext());

        var observation = startPromptObservation(observationRegistry.getCurrentObservation(), sessionId,
                request.message());
        try (var scope = observation.openScope()) {
            // Resolved before anything else: it decides the agent (by the caller's context), the
            // model, the credential, the prompt and which MCP servers to even open a connection to
            // — and refuses an over-budget request here rather than after spending on it.
            AgentConfig config = resolveConfig(authorization, request, console);
            tagAgent(observation, config);

            var input = checkInput(observation, config, request.message(), authorization);
            if (input.blocked()) {
                return GuardrailRunner.refusal(GuardrailRunner.Side.INPUT, input);
            }
            String message = input.text();

            try (var tools = mcpFactory.createTools(config.mcpUrls(), authorization)) {
                var peers = peerTools.toolsFor(config, authorization, A2aHop.origin());
                var toolCallbacks = allTools(config, tools, peers);
                tagTools(observation, tools, toolCallbacks.size());
                // Only a hard stop when there is nothing at all to call. An agent whose MCP
                // servers are all down but which still has a RAG source or a peer agent can
                // answer through those, and refusing here would take that away.
                if (tools.hasNoServers() && config.rags().isEmpty() && peers.isEmpty()) {
                    String err = "No hay ningún servidor MCP disponible (" + tools.expectedServers()
                            + " configurados, 0 conectados) ni ninguna fuente RAG. No puedo "
                            + "responder sin acceso a las herramientas.";
                    log.warn("Chat aborted session={}: {}", sessionId, err);
                    tagResponse(observation, err);
                    outcome(observation, "no_tools");
                    return err;
                }
                String systemPrompt = buildSystemPrompt(config.systemPrompt(),
                        tools.getServerSystemContext(), sessionId, request.screenRoute(), peers);
                var history = conversationStore.getHistory(sessionId);

                var turn = agentTurn.call(config, systemPrompt, history, message,
                        toolCallbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]));
                String content = turn.content();
                int inputTokens = turn.inputTokens(), outputTokens = turn.outputTokens(),
                        totalTokens = turn.totalTokens();
                log.info("Chat response session={}: {} chars, tokens={}/{}/{}",
                        sessionId, content != null ? content.length() : 0,
                        inputTokens, outputTokens, totalTokens);

                String raw = (content != null && !content.isBlank()) ? content : "(sin respuesta)";
                var checked = checkOutput(config, raw, authorization);
                String result = parseNavigation(checked.text()).cleanText();
                conversationStore.addExchange(sessionId, message, result);
                conversationStore.accumulateTokens(sessionId, inputTokens, outputTokens, totalTokens);
                usageReporter.report(config.agentId(), config.llm().id(), config.llm().model(),
                        inputTokens, outputTokens, totalTokens,
                        jwtIdentityReader.read(authorization), sessionId);
                tagResponse(observation, result);
                outcome(observation, checked.outcome());
                return result;
            }
        } catch (NoConfigurationException | ChatClientRegistry.UnsupportedProviderException e) {
            // Not an error during the prompt — a misconfiguration. The message is written for
            // whoever can fix it, so it is returned as it is rather than wrapped in a class name.
            log.warn("Chat aborted session={}: {}", sessionId, e.getMessage());
            tagResponse(observation, e.getMessage());
            outcome(observation, "refused");
            return e.getMessage();
        } catch (Exception e) {
            log.error("Error en chat session={} — {}: {}", sessionId, e.getClass().getName(), e.getMessage(), e);
            observation.error(e);
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            observation.stop();
        }
    }

    // ── /stream  (POST, SSE) ─────────────────────────────────────────────────

    /** How often an idle stream says it is still alive, as an SSE comment no client renders. */
    static final Duration KEEP_ALIVE = Duration.ofSeconds(5);

    /** As {@link #stream(ChatRequest, String, String, String)}, from no console in particular. */
    public Flux<ServerSentEvent<String>> stream(ChatRequest request, String authorization) {
        return stream(request, authorization, null, null);
    }

    /**
     * SSE endpoint: the answer as it is written, and what the agent is doing until then.
     *
     * Request body: {@link ChatRequest} (JSON) — includes {@code message}, {@code sessionId}
     * and optionally {@code menuContext} (only needs to be sent when the menu changes).
     *
     * <p>SSE events, every one a single {@code data:} payload, in this order:
     * <ul>
     *   <li>{@code {"event":"agent-status","detail":{"phase":"resolving|guardrails|connecting|thinking","text":"…",…}}}
     *       — each phase of the prompt, with a sentence the panel can show as it is;</li>
     *   <li>{@code {"event":"agent-tool","detail":{"name":"…","server":"…","kind":"mcp|rag|a2a","phase":"start|end","ms":N,"error":"…"}}}
     *       — each tool call, as it starts and as it ends;</li>
     *   <li>{@code {"event":"agent-delta","detail":{"text":"…"}}} — a piece of the answer, to append.
     *       Never sent when the route has output guardrails: a piece already on the wire cannot be
     *       taken back, so then the answer leaves only once they have read it. Navigation markers
     *       never appear in one;</li>
     *   <li>{@code {"inputTokens":N,"outputTokens":M,"totalTokens":T}} — the session's usage so far,
     *       once, at the end;</li>
     *   <li>{@code {"event":"navigation-requested","detail":{…}}} — per [NAVIGATE:{…}] marker;</li>
     *   <li>the answer, cleaned, as plain text — last. A client that showed the deltas replaces
     *       them with it; one that knows nothing of deltas shows only this, as before.</li>
     * </ul>
     * An idle stream sends an SSE comment every {@link #KEEP_ALIVE}. A failure is
     * {@code {"event":"agent-error","detail":{"message":"…"}}}, last.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request,
                                                @RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestHeader(value = CHANNEL_HEADER, required = false) String channel,
                                                @RequestHeader(value = DEFAULT_AGENT_HEADER, required = false) String defaultAgent) {
        var console = new Console(channel, defaultAgent);
        String sessionId = request.sessionId();
        log.info("Stream request session={}: {} chars", sessionId, length(request.message()));
        log.debug("Stream request session={}: '{}'", sessionId, request.message());

        // Cache menu if provided
        menuContextStore.update(sessionId, request.menuContext());

        var history = conversationStore.getHistory(sessionId);

        // Taken here, on the request's thread: the prompt runs on another, which does not inherit
        // it, and without it the prompt's span would start a trace of its own.
        Observation requestObservation = observationRegistry.getCurrentObservation();

        // One sink per request, fed from wherever the prompt is at: the setup (blocking — the
        // control plane, the guardrails, the MCP handshakes) on a worker, the model's chunks on
        // the model's threads, the tool events on the tools'. FluxSink serialises them.
        Flux<ServerSentEvent<String>> events = Flux.create(sink -> {
            var run = new StreamRun(sink, request, authorization, console, sessionId, history, requestObservation);
            sink.onDispose(run::cancel);
            Schedulers.boundedElastic().schedule(run::start);
        });

        return events.publish(shared -> Flux.merge(shared,
                Flux.interval(KEEP_ALIVE, KEEP_ALIVE)
                        .map(i -> ServerSentEvent.<String>builder().comment("keep-alive").build())
                        .takeUntilOther(shared.then())));
    }

    /** {@code event} first, so the payload reads as what it is. */
    private static java.util.Map<String, Object> eventMap(String event, Object detail) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("event", event);
        map.put("detail", detail);
        return map;
    }

    private ServerSentEvent<String> jsonEvent(String event, java.util.Map<String, ?> detail) {
        try {
            return ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(eventMap(event, detail)))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().data("{\"event\":\"" + event + "\",\"detail\":{}}").build();
        }
    }

    /**
     * One /stream prompt, from the first status line to the last event. Also the prompt's
     * {@link ToolProgressListener}: its tools report here, and it turns that into events.
     */
    private final class StreamRun implements ToolProgressListener {

        private final FluxSink<ServerSentEvent<String>> sink;
        private final ChatRequest request;
        private final String authorization;
        private final Console console;
        private final String sessionId;
        private final List<org.springframework.ai.chat.messages.Message> history;
        private final Observation requestObservation;

        /** Set once: the first of finishing, failing and the client going away wins. */
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile Observation observation;
        private volatile PerRequestMcpClientFactory.PerRequestTools tools;
        private volatile Disposable llm;

        // The answer as it arrives. Guarded by this.
        private final NavigationMarkerFilter markers = new NavigationMarkerFilter();
        /** Everything the model wrote, every round of the tool loop. */
        private final StringBuilder all = new StringBuilder();
        /** What it wrote since its last tool call: the answer, as /chat's call() returns it. */
        private final StringBuilder round = new StringBuilder();
        private boolean toolSinceText;
        private boolean shownAny;
        private int[] usage = {0, 0, 0};
        private boolean deltas;

        StreamRun(FluxSink<ServerSentEvent<String>> sink, ChatRequest request, String authorization,
                  Console console, String sessionId,
                  List<org.springframework.ai.chat.messages.Message> history, Observation requestObservation) {
            this.sink = sink;
            this.request = request;
            this.authorization = authorization;
            this.console = console;
            this.sessionId = sessionId;
            this.history = history;
            this.requestObservation = requestObservation;
        }

        void start() {
            var observation = startPromptObservation(requestObservation, sessionId, request.message());
            this.observation = observation;
            try (var scope = observation.openScope()) {
                run(observation);
            } catch (NoConfigurationException | ChatClientRegistry.UnsupportedProviderException e) {
                refused(e);
            } catch (Exception e) {
                failed(e);
            }
        }

        /** Same order as /chat: resolve, check the input, connect, then the model. */
        private void run(Observation observation) {
            status("resolving", "Preparando el agente…", java.util.Map.of());
            AgentConfig config = resolveConfig(authorization, request, console);
            tagAgent(observation, config);
            if (!config.guardrailsOrNone().input().isEmpty()) {
                status("guardrails", "Revisando la petición…", java.util.Map.of());
            }
            var input = checkInput(observation, config, request.message(), authorization);
            if (input.blocked()) {
                finish(new LlmResult(GuardrailRunner.refusal(GuardrailRunner.Side.INPUT, input), 0, 0, 0));
                return;
            }
            String message = input.text();

            int expected = config.mcpUrls() == null ? 0 : config.mcpUrls().size();
            if (expected > 0) {
                status("connecting", "Conectando con " + expected
                        + (expected == 1 ? " servidor MCP…" : " servidores MCP…"),
                        java.util.Map.of("expected", expected));
            }
            var tools = mcpFactory.createTools(config.mcpUrls(), authorization, this);
            this.tools = tools;
            if (done.get()) {
                // The client left while the handshakes ran.
                tools.close();
                return;
            }
            var peers = peerTools.toolsFor(config, authorization, A2aHop.origin(), this);
            var toolCallbacks = allTools(config, tools, peers, this);
            tagTools(observation, tools, toolCallbacks.size());
            status("connecting", (expected > 0
                            ? "Conectado a " + tools.connectedServers() + "/" + expected + " servidores MCP · "
                            : "") + toolCallbacks.size() + (toolCallbacks.size() == 1 ? " herramienta" : " herramientas"),
                    java.util.Map.of("connected", tools.connectedServers(), "expected", expected,
                            "tools", toolCallbacks.size()));
            if (tools.hasNoServers() && config.rags().isEmpty() && peers.isEmpty()) {
                String err = "No hay ningún servidor MCP disponible ("
                        + tools.expectedServers() + " configurados, 0 conectados) ni "
                        + "ninguna fuente RAG. No puedo responder sin acceso a las "
                        + "herramientas.";
                log.warn("Stream aborted session={}: no tools at all", sessionId);
                tagResponse(observation, err);
                outcome(observation, "no_tools");
                finish(new LlmResult(err, 0, 0, 0));
                return;
            }
            String systemPrompt = buildSystemPrompt(config.systemPrompt(),
                    tools.getServerSystemContext(), sessionId, request.screenRoute(), peers);
            // A route with output guardrails gets no deltas: see checkOutput.
            synchronized (this) {
                deltas = config.guardrailsOrNone().output().isEmpty();
            }
            status("thinking", "Pensando…", java.util.Map.of());

            llm = agentTurn.stream(config, systemPrompt, history, message,
                            toolCallbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]))
                    .doOnNext(this::chunk)
                    // The end is blocking — output guardrails over A2A — so not on the thread that
                    // delivered the model's last chunk.
                    .then(Mono.fromCallable(() -> complete(config, message))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation))
                    .subscribe(this::finish, e -> {
                        if (e instanceof NoConfigurationException
                                || e instanceof ChatClientRegistry.UnsupportedProviderException) {
                            refused(e);
                        } else {
                            failed(e);
                        }
                    });
            if (done.get()) {
                llm.dispose();
            }
        }

        private synchronized void chunk(ChatResponse response) {
            int[] u = AgentTurn.usageOf(response);
            if (u[0] > 0 || u[2] > 0) {
                usage = u;
            }
            String text = AgentTurn.textOf(response);
            if (text == null || text.isEmpty()) {
                return;
            }
            boolean newRound = toolSinceText;
            if (newRound) {
                round.setLength(0);
                toolSinceText = false;
            }
            all.append(text);
            round.append(text);
            if (!deltas) {
                return;
            }
            String safe = markers.feed(text);
            if (safe.isEmpty()) {
                return;
            }
            if (newRound && shownAny) {
                // What the model said before calling a tool and what it says after are two
                // paragraphs, not one run-on sentence.
                safe = "\n\n" + safe.stripLeading();
            }
            if (!safe.isBlank()) {
                shownAny = true;
            }
            sink.next(jsonEvent("agent-delta", java.util.Map.of("text", safe)));
        }

        /** The answer is complete: check it, record it, and say what it cost. */
        private LlmResult complete(AgentConfig config, String message) {
            Observation observation = this.observation;
            try (var scope = observation.openScope()) {
                String content;
                int[] cost;
                synchronized (this) {
                    content = round.toString().isBlank() ? all.toString() : round.toString();
                    cost = usage;
                }
                log.info("Stream completed session={}: {} chars, tokens={}/{}/{}",
                        sessionId, content.length(), cost[0], cost[1], cost[2]);
                if (!config.guardrailsOrNone().output().isEmpty()) {
                    status("guardrails", "Revisando la respuesta…", java.util.Map.of());
                }
                var checked = checkOutput(config, !content.isBlank() ? content : "(sin respuesta)", authorization);
                String raw = checked.text();
                conversationStore.addExchange(sessionId, message, raw);
                conversationStore.accumulateTokens(sessionId, cost[0], cost[1], cost[2]);
                usageReporter.report(config.agentId(), config.llm().id(), config.llm().model(),
                        cost[0], cost[1], cost[2], jwtIdentityReader.read(authorization), sessionId);
                tagResponse(observation, raw);
                outcome(observation, checked.outcome());
                int[] cumulative = conversationStore.getTotalTokens(sessionId);
                return new LlmResult(raw, cumulative[0], cumulative[1], cumulative[2]);
            }
        }

        // ── ToolProgressListener ─────────────────────────────────────────────

        @Override
        public void toolStarted(String name, String server, String kind) {
            synchronized (this) {
                toolSinceText = true;
            }
            sink.next(jsonEvent("agent-tool", toolDetail(name, server, kind, "start", null, null)));
        }

        @Override
        public void toolEnded(String name, String server, String kind, long millis, String error) {
            sink.next(jsonEvent("agent-tool", toolDetail(name, server, kind, "end", millis, error)));
            status("thinking", "Pensando…", java.util.Map.of());
        }

        private java.util.Map<String, Object> toolDetail(String name, String server, String kind, String phase,
                                                         Long millis, String error) {
            var detail = new java.util.LinkedHashMap<String, Object>();
            detail.put("name", name);
            if (server != null) {
                detail.put("server", server);
            }
            if (kind != null) {
                detail.put("kind", kind);
            }
            detail.put("phase", phase);
            if (millis != null) {
                detail.put("ms", millis);
            }
            if (error != null) {
                detail.put("error", error);
            }
            return detail;
        }

        private void status(String phase, String text, java.util.Map<String, ?> extra) {
            var detail = new java.util.LinkedHashMap<String, Object>();
            detail.put("phase", phase);
            detail.put("text", text);
            detail.putAll(extra);
            sink.next(jsonEvent("agent-status", detail));
        }

        // ── The end, whichever it is ─────────────────────────────────────────

        /** The last events, as /stream has always ended: usage, navigation, the answer. */
        private void finish(LlmResult result) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            closeTools();
            var parsed = parseNavigation(result.content() == null ? "" : result.content());
            sink.next(tokenEvent(result.inputTokens(), result.outputTokens(), result.totalTokens()));
            parsed.navEvents().forEach(sink::next);
            sink.next(contentEvent(parsed.cleanText()));
            sink.complete();
            stopObservation();
        }

        /**
         * A misconfiguration is not a stack trace: it is a sentence for whoever can fix it, and it
         * reaches the panel as the answer rather than as an error event.
         */
        private void refused(Throwable e) {
            log.warn("Stream aborted session={}: {}", sessionId, e.getMessage());
            Observation observation = this.observation;
            if (observation != null) {
                tagResponse(observation, e.getMessage());
                outcome(observation, "refused");
            }
            finish(new LlmResult(e.getMessage(), 0, 0, 0));
        }

        private void failed(Throwable e) {
            log.error("Stream error session={} — {}: {}", sessionId, e.getClass().getName(), e.getMessage(), e);
            if (!done.compareAndSet(false, true)) {
                return;
            }
            Observation observation = this.observation;
            if (observation != null) {
                observation.error(e);
            }
            closeTools();
            sink.next(errorEvent(e));
            sink.complete();
            stopObservation();
        }

        /** The client went away: stop the model, close the connections, end the span. */
        void cancel() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            log.info("Stream cancelled by the client session={}", sessionId);
            var llm = this.llm;
            if (llm != null) {
                llm.dispose();
            }
            closeTools();
            Observation observation = this.observation;
            if (observation != null) {
                outcome(observation, "cancelled");
            }
            stopObservation();
        }

        private void closeTools() {
            var tools = this.tools;
            this.tools = null;
            if (tools != null) {
                tools.close();
            }
        }

        private void stopObservation() {
            var observation = this.observation;
            this.observation = null;
            if (observation != null) {
                observation.stop();
            }
        }
    }
}
