package io.mateu.ecdemo1.iacp.infra.config;

import io.mateu.ecdemo1.iacp.application.out.query.AgentQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.LlmQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.McpQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.RagQueryService;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentCommand;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.llm.create.CreateLlmCommand;
import io.mateu.ecdemo1.iacp.application.usecases.llm.create.CreateLlmUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.create.CreateMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.create.CreateMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.rag.create.CreateRagCommand;
import io.mateu.ecdemo1.iacp.application.usecases.rag.create.CreateRagUseCase;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagKind;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmProvider;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpTransport;
import io.mateu.ecdemo1.iacp.infra.out.crypto.AesGcmSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts this deployment's own agent in the catalogues, once, on a control plane that has never had
 * anything in it.
 *
 * <p>Without this, moving the agent's configuration out of a properties file would have made a
 * fresh {@code deploy.sh} produce a chat panel that does not work until somebody opens the control
 * console and types four things in. The point of a control plane is to be able to change the
 * configuration, not to have to invent it — so the deployment still comes up working, and what it
 * comes up with is visible and editable instead of compiled in.
 *
 * <p><strong>Only when all four catalogues are empty.</strong> Not "create if missing" per entry:
 * that would resurrect an MCP server someone deliberately deleted on the next restart, which is
 * the kind of helpfulness that is impossible to argue with and impossible to switch off. One
 * decision, taken once, on a control plane that is demonstrably brand new.
 *
 * <p>Consequently it is also not an upgrade path. A control plane that already holds anything is
 * left exactly as it is.
 */
@Configuration
public class CatalogueSeeder {

    private static final Logger log = LoggerFactory.getLogger(CatalogueSeeder.class);

    @Bean
    ApplicationRunner seedCatalogues(
            LlmQueryService llms, McpQueryService mcps, RagQueryService rags, AgentQueryService agents,
            CreateLlmUseCase createLlm, CreateMcpUseCase createMcp, CreateRagUseCase createRag,
            CreateAgentUseCase createAgent,
            AesGcmSecretCipher cipher,
            HandbookIngester handbookIngester,
            @Value("${cp.gitops.enabled:false}") boolean gitopsEnabled,
            @Value("${cp.seed.agent-id:console-agent}") String agentId,
            @Value("${cp.seed.anthropic-api-key:}") String anthropicApiKey,
            @Value("${cp.seed.orchestrator-url:}") String orchestratorUrl,
            @Value("${cp.seed.forms-url:}") String formsUrl,
            @Value("${cp.seed.booking-url:}") String bookingUrl,
            @Value("${cp.seed.rag-url:}") String ragUrl,
            @Value("${cp.seed.embedding-model:intfloat/multilingual-e5-small}") String embeddingModel,
            @Value("${cp.seed.embedding-base-url:http://embeddings/v1}") String embeddingBaseUrl,
            @Value("${cp.seed.embedding-api-key:tei-local}") String embeddingApiKey) {
        return args -> {
            if (gitopsEnabled) {
                // Git provides the catalogues when GitOps is on. Seeding here would create entries
                // the console owns, which the reconciler then refuses to touch — a confusing
                // half-state where the repo cannot manage its own agent. Let git seed it instead.
                log.info("GitOps is enabled — the repo owns the catalogues, so nothing is seeded.");
                return;
            }
            if (llms.count() > 0 || mcps.count() > 0 || rags.count() > 0 || agents.count() > 0) {
                log.info("Catalogues already hold something — nothing seeded.");
                return;
            }
            if (!cipher.isConfigured()) {
                // Seeding an LLM would mean encrypting a credential, and there is nothing to
                // encrypt with. Leaving the catalogues empty is better than seeding half of the
                // agent and then never seeding the rest, because this only ever runs on empty.
                log.error("Catalogues are empty and CP_CRYPTO_KEY is not set, so nothing was "
                        + "seeded. Set it and restart, or create the entries by hand.");
                return;
            }

            log.info("Catalogues are empty — seeding this deployment's own agent.");

            createLlm.handle(new CreateLlmCommand("anthropic", "Anthropic",
                    LlmProvider.ANTHROPIC, "claude-sonnet-4-5", null, 0.1, 4096, null, null, null,
                    // Empty is fine and expected: the key is bought, not generated, so a
                    // deployment without one gets a catalogued model that says "missing" until
                    // someone fills it in from the console. That is the same failure as before,
                    // now with a place to fix it.
                    anthropicApiKey));

            var mcpIds = new ArrayList<String>();
            seedMcp(createMcp, mcpIds, "orchestrator", "Orchestrator", orchestratorUrl,
                    "The workflow engine: processes, definitions, step executions.");
            seedMcp(createMcp, mcpIds, "forms", "Forms engine", formsUrl,
                    "Form definitions and the human tasks waiting to be done.");
            seedMcp(createMcp, mcpIds, "booking", "Booking service", bookingUrl,
                    "Bookings: create, list and change the status of one.");

            // A retrieval source, and an embedding model for it. The model is the in-cluster Text
            // Embeddings Inference pod — an OpenAI-shaped endpoint, because Anthropic has no
            // embeddings API — running a multilingual model so questions in either language embed.
            // Its credential is a placeholder: TEI needs none, but the pgvector store refuses to
            // embed against a model with a blank one, so a non-blank dummy stands in for it.
            var ragIds = new ArrayList<String>();
            if (ragUrl != null && !ragUrl.isBlank()) {
                createLlm.handle(new CreateLlmCommand("embeddings", "Embeddings (local, multilingual)",
                        LlmProvider.OPENAI_COMPATIBLE, embeddingModel, embeddingBaseUrl, 0.0, 256, null, null, null,
                        embeddingApiKey));
                createRag.handle(new CreateRagCommand("handbook", "Ops handbook",
                        RagKind.PGVECTOR, ragUrl, "handbook_vectors", "embeddings", 5,
                        "Notes about running this deployment: the services, identity and access, the "
                                + "control plane, and what to check when something is stuck. Search "
                                + "here for how-to and operational questions."));
                ragIds.add("handbook");
            }

            // The RAG source IS put on the agent, because it will not be empty: the handbook is
            // ingested below at this same first start. An empty source on an agent is worse than
            // none — a tool that always answers "nothing found" — which is exactly why the ingest
            // and the attach happen together and only here.
            createAgent.handle(new CreateAgentCommand(agentId, "Console agent",
                    defaultPrompt(), "anthropic", mcpIds, ragIds, List.of(),
                    "The agent behind the demo console's chat panel. Seeded on first start."));

            // Fill the source in the background: the embedding pod may still be starting, so this
            // retries rather than blocks. Only when the source was actually seeded.
            if (!ragIds.isEmpty()) {
                handbookIngester.ingestInBackground("handbook");
            }

            log.info("Seeded LLM 'anthropic'{}, {} MCP server(s), {} RAG source(s) and agent '{}'. "
                            + "{}",
                    anthropicApiKey == null || anthropicApiKey.isBlank() ? " (no credential yet)" : "",
                    mcpIds.size(), ragIds.size(), agentId,
                    ragIds.isEmpty() ? "No RAG source seeded (no rag-url)."
                            : "The handbook is being ingested in the background.");
        };
    }

    private void seedMcp(CreateMcpUseCase createMcp, List<String> into,
                         String id, String name, String url, String description) {
        if (url == null || url.isBlank()) {
            // A URL that was not passed in is a service that is not in this deployment. Better an
            // agent with fewer servers than one pointed at a host that does not resolve.
            log.info("No URL for MCP server '{}' — not seeded.", id);
            return;
        }
        createMcp.handle(new CreateMcpCommand(id, name, url, McpTransport.SSE, 60L, description));
        into.add(id);
    }

    private String defaultPrompt() {
        try {
            return new ClassPathResource("default-agent-prompt.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // The seeded agent is editable, so a fallback beats refusing to seed. It is short on
            // purpose: anything longer pretending to be the real prompt would be worse.
            log.warn("Could not read default-agent-prompt.txt, seeding a minimal prompt", e);
            return "Eres un asistente. Responde únicamente llamando a las herramientas disponibles, "
                    + "y si una falla, di exactamente qué falló en lugar de inventar la respuesta.";
        }
    }
}
