package io.mateu.ecdemo1.iacp.application.usecases.gitops;

import io.mateu.ecdemo1.iacp.application.out.gitops.CatalogueSource;
import io.mateu.ecdemo1.iacp.application.out.gitops.CredentialResolver;
import io.mateu.ecdemo1.iacp.application.out.gitops.DesiredCatalogue;
import io.mateu.ecdemo1.iacp.application.out.gitops.GitopsManagedRegistry;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.AgentManifest;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.ApiMcpManifest;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.BudgetManifest;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.LlmManifest;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.McpManifest;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.RagManifest;
import io.mateu.ecdemo1.iacp.application.out.gitops.manifest.RouteManifest;
import io.mateu.ecdemo1.iacp.application.out.query.AgentQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.ApiMcpQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.BudgetQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.LlmQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.McpQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.RagQueryService;
import io.mateu.ecdemo1.iacp.application.out.query.RouteQueryService;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.create.CreateApiMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.create.CreateApiMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.delete.DeleteApiMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.delete.DeleteApiMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.exposetools.ExposeApiToolsCommand;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.exposetools.ExposeApiToolsUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.replacecredential.ReplaceApiMcpCredentialCommand;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.replacecredential.ReplaceApiMcpCredentialUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.update.UpdateApiMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.apimcp.update.UpdateApiMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.budget.create.CreateBudgetCommand;
import io.mateu.ecdemo1.iacp.application.usecases.budget.create.CreateBudgetUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.budget.delete.DeleteBudgetCommand;
import io.mateu.ecdemo1.iacp.application.usecases.budget.delete.DeleteBudgetUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.budget.update.UpdateBudgetCommand;
import io.mateu.ecdemo1.iacp.application.usecases.budget.update.UpdateBudgetUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.route.create.CreateRouteCommand;
import io.mateu.ecdemo1.iacp.application.usecases.route.create.CreateRouteUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.route.delete.DeleteRouteCommand;
import io.mateu.ecdemo1.iacp.application.usecases.route.delete.DeleteRouteUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.route.update.UpdateRouteCommand;
import io.mateu.ecdemo1.iacp.application.usecases.route.update.UpdateRouteUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentCommand;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.agent.delete.DeleteAgentCommand;
import io.mateu.ecdemo1.iacp.application.usecases.agent.delete.DeleteAgentUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.agent.update.UpdateAgentCommand;
import io.mateu.ecdemo1.iacp.application.usecases.agent.update.UpdateAgentUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.llm.create.CreateLlmCommand;
import io.mateu.ecdemo1.iacp.application.usecases.llm.create.CreateLlmUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.llm.delete.DeleteLlmCommand;
import io.mateu.ecdemo1.iacp.application.usecases.llm.delete.DeleteLlmUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.llm.replacecredential.ReplaceLlmCredentialCommand;
import io.mateu.ecdemo1.iacp.application.usecases.llm.replacecredential.ReplaceLlmCredentialUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.llm.update.UpdateLlmCommand;
import io.mateu.ecdemo1.iacp.application.usecases.llm.update.UpdateLlmUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.create.CreateMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.create.CreateMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.delete.DeleteMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.delete.DeleteMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.update.UpdateMcpCommand;
import io.mateu.ecdemo1.iacp.application.usecases.mcp.update.UpdateMcpUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.rag.create.CreateRagCommand;
import io.mateu.ecdemo1.iacp.application.usecases.rag.create.CreateRagUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.rag.delete.DeleteRagCommand;
import io.mateu.ecdemo1.iacp.application.usecases.rag.delete.DeleteRagUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.rag.update.UpdateRagCommand;
import io.mateu.ecdemo1.iacp.application.usecases.rag.update.UpdateRagUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Brings the live catalogues in line with what the repo declares — but only over the entries git
 * owns.
 *
 * <p>The rule this enforces is provenance, not a wholesale overwrite. An entry git has created is
 * recorded in the {@link GitopsManagedRegistry}; on each sync those are updated to match the repo,
 * and any that has disappeared from the repo is deleted. An entry the console made is in no
 * registry, so it is never touched — not updated, not deleted — and an id the console already holds
 * is left alone even when the repo names the same one, rather than have a sync silently overwrite a
 * person's work. Git cleans up after git; the console cleans up after the console.
 *
 * <p><strong>All-or-nothing on the read.</strong> The source throws rather than return a partial
 * catalogue, and a throw here aborts the whole reconcile before a single delete — because a fetch
 * that came back empty by accident would otherwise read as "the repo was emptied" and take the
 * git-managed catalogue with it. Per-entry failures below are different: they are logged and
 * stepped over, so one malformed file does not stop the rest from converging.
 *
 * <p>Order matters for the references between catalogues. Upserts run models, servers and sources
 * before the agents that name them; deletes run in reverse, agents before the things they point at,
 * so a sync never briefly leaves an agent pointing at a model that has already gone.
 *
 * <p>One reconcile at a time. The webhook and the optional poll can both fire, so a lock makes a
 * second call step aside rather than interleave with the first.
 */
@Service
@ConditionalOnProperty(name = "cp.gitops.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ReconcileCatalogueUseCase {

    private static final String LLM = "llm";
    private static final String MCP = "mcp";
    private static final String API_MCP = "apimcp";
    private static final String RAG = "rag";
    private static final String AGENT = "agent";
    private static final String BUDGET = "budget";
    private static final String ROUTE = "route";

    private final CatalogueSource source;
    private final CredentialResolver credentials;
    private final GitopsManagedRegistry registry;

    private final LlmQueryService llmQuery;
    private final McpQueryService mcpQuery;
    private final ApiMcpQueryService apiMcpQuery;
    private final RagQueryService ragQuery;
    private final AgentQueryService agentQuery;
    private final BudgetQueryService budgetQuery;
    private final RouteQueryService routeQuery;

    private final CreateLlmUseCase createLlm;
    private final UpdateLlmUseCase updateLlm;
    private final DeleteLlmUseCase deleteLlm;
    private final ReplaceLlmCredentialUseCase replaceCredential;
    private final CreateMcpUseCase createMcp;
    private final UpdateMcpUseCase updateMcp;
    private final DeleteMcpUseCase deleteMcp;
    private final CreateApiMcpUseCase createApiMcp;
    private final UpdateApiMcpUseCase updateApiMcp;
    private final DeleteApiMcpUseCase deleteApiMcp;
    private final ReplaceApiMcpCredentialUseCase replaceApiMcpCredential;
    private final ExposeApiToolsUseCase exposeApiTools;
    private final CreateRagUseCase createRag;
    private final UpdateRagUseCase updateRag;
    private final DeleteRagUseCase deleteRag;
    private final CreateAgentUseCase createAgent;
    private final UpdateAgentUseCase updateAgent;
    private final DeleteAgentUseCase deleteAgent;
    private final CreateBudgetUseCase createBudget;
    private final UpdateBudgetUseCase updateBudget;
    private final DeleteBudgetUseCase deleteBudget;
    private final CreateRouteUseCase createRoute;
    private final UpdateRouteUseCase updateRoute;
    private final DeleteRouteUseCase deleteRoute;

    private final ReentrantLock lock = new ReentrantLock();

    /** Running counts of one reconcile, for the one summary line it logs. */
    private static final class Report {
        int created, updated, deleted, skipped, failed;
    }

    /**
     * Reconcile once. {@code trigger} is only for the log — "webhook", "startup", "poll" — so a
     * reader can tell what set it off.
     */
    public void reconcile(String trigger) {
        if (!lock.tryLock()) {
            log.info("GitOps reconcile ({}) skipped — one is already running.", trigger);
            return;
        }
        try {
            DesiredCatalogue desired = source.fetch();
            var report = new Report();

            desired.llms().forEach(m -> upsertLlm(m, report));
            desired.mcps().forEach(m -> upsertMcp(m, report));
            desired.apiMcps().forEach(m -> upsertApiMcp(m, report));
            desired.rags().forEach(m -> upsertRag(m, report));
            desired.agents().forEach(m -> upsertAgent(m, report));
            desired.budgets().forEach(m -> upsertBudget(m, report));
            desired.routes().forEach(m -> upsertRoute(m, report));

            deleteRemoved(ROUTE, ids(desired.routes(), RouteManifest::id),
                    id -> routeQuery.getById(id).isPresent(),
                    id -> deleteRoute.handle(new DeleteRouteCommand(List.of(id))), report);
            deleteRemoved(BUDGET, ids(desired.budgets(), BudgetManifest::id),
                    id -> budgetQuery.getById(id).isPresent(),
                    id -> deleteBudget.handle(new DeleteBudgetCommand(List.of(id))), report);
            deleteRemoved(AGENT, ids(desired.agents(), AgentManifest::id),
                    id -> agentQuery.getById(id).isPresent(),
                    id -> deleteAgent.handle(new DeleteAgentCommand(List.of(id))), report);
            deleteRemoved(RAG, ids(desired.rags(), RagManifest::id),
                    id -> ragQuery.getById(id).isPresent(),
                    id -> deleteRag.handle(new DeleteRagCommand(List.of(id))), report);
            deleteRemoved(API_MCP, ids(desired.apiMcps(), ApiMcpManifest::id),
                    id -> apiMcpQuery.getById(id).isPresent(),
                    id -> deleteApiMcp.handle(new DeleteApiMcpCommand(List.of(id))), report);
            deleteRemoved(MCP, ids(desired.mcps(), McpManifest::id),
                    id -> mcpQuery.getById(id).isPresent(),
                    id -> deleteMcp.handle(new DeleteMcpCommand(List.of(id))), report);
            deleteRemoved(LLM, ids(desired.llms(), LlmManifest::id),
                    id -> llmQuery.getById(id).isPresent(),
                    id -> deleteLlm.handle(new DeleteLlmCommand(List.of(id))), report);

            log.info("GitOps reconcile ({}) done: {} created, {} updated, {} deleted, {} skipped "
                            + "(console-owned), {} failed.", trigger, report.created, report.updated,
                    report.deleted, report.skipped, report.failed);
        } catch (Exception e) {
            log.error("GitOps reconcile ({}) failed to read the repo — catalogues left unchanged: {}",
                    trigger, e.toString(), e);
        } finally {
            lock.unlock();
        }
    }

    // ── upserts ──────────────────────────────────────────────────────────────────────────────

    private void upsertLlm(LlmManifest m, Report report) {
        guardAndRun(LLM, m.id(), () -> llmQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            if (exists) {
                updateLlm.handle(new UpdateLlmCommand(m.id(), name, m.provider(), m.model(),
                        m.baseUrl(), m.temperature(), m.maxTokens(),
                        m.priceCurrency(), m.inputPricePerMillion(), m.outputPricePerMillion(), enabled(m.enabled())));
            } else {
                createLlm.handle(new CreateLlmCommand(m.id(), name, m.provider(), m.model(),
                        m.baseUrl(), m.temperature(), m.maxTokens(),
                        m.priceCurrency(), m.inputPricePerMillion(), m.outputPricePerMillion(), null));
                if (!enabled(m.enabled())) {
                    updateLlm.handle(new UpdateLlmCommand(m.id(), name, m.provider(), m.model(),
                            m.baseUrl(), m.temperature(), m.maxTokens(),
                        m.priceCurrency(), m.inputPricePerMillion(), m.outputPricePerMillion(), false));
                }
            }
            resolveCredential(m);
        });
    }

    private void resolveCredential(LlmManifest m) {
        if (m.credentialEnv() == null || m.credentialEnv().isBlank()) {
            return;
        }
        var key = credentials.resolve(m.credentialEnv());
        if (key == null || key.isBlank()) {
            log.warn("LLM '{}' names credentialEnv '{}', which resolves to nothing — leaving its "
                    + "stored credential as it is.", m.id(), m.credentialEnv());
            return;
        }
        replaceCredential.handle(new ReplaceLlmCredentialCommand(m.id(), key));
    }

    private void upsertMcp(McpManifest m, Report report) {
        guardAndRun(MCP, m.id(), () -> mcpQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            if (exists) {
                updateMcp.handle(new UpdateMcpCommand(m.id(), name, m.url(), m.transport(),
                        m.timeoutSeconds(), m.description(), enabled(m.enabled())));
            } else {
                createMcp.handle(new CreateMcpCommand(m.id(), name, m.url(), m.transport(),
                        m.timeoutSeconds(), m.description()));
                if (!enabled(m.enabled())) {
                    updateMcp.handle(new UpdateMcpCommand(m.id(), name, m.url(), m.transport(),
                            m.timeoutSeconds(), m.description(), false));
                }
            }
        });
    }

    /**
     * An API-backed MCP entry, its credential and its offer.
     *
     * <p>Three things and not one, because the aggregate keeps them apart for reasons that survive
     * into the repo: the credential can only be replaced, never read back, and the offer is
     * replaced whole rather than merged — an entry that is half of an old import and half of a new
     * one is the state {@code exposeExactly} exists to prevent.
     */
    private void upsertApiMcp(ApiMcpManifest m, Report report) {
        guardAndRun(API_MCP, m.id(), () -> apiMcpQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            if (exists) {
                updateApiMcp.handle(new UpdateApiMcpCommand(m.id(), name, m.apiKind(), m.baseUrl(),
                        m.specUrl(), m.description(), enabled(m.enabled())));
            } else {
                createApiMcp.handle(new CreateApiMcpCommand(m.id(), name, m.apiKind(), m.baseUrl(),
                        m.specUrl(), m.description()));
                if (!enabled(m.enabled())) {
                    updateApiMcp.handle(new UpdateApiMcpCommand(m.id(), name, m.apiKind(),
                            m.baseUrl(), m.specUrl(), m.description(), false));
                }
            }
            resolveApiCredential(m);
            exposeTools(m);
        });
    }

    private void resolveApiCredential(ApiMcpManifest m) {
        if (m.credentialEnv() == null || m.credentialEnv().isBlank()) {
            return;
        }
        var secret = credentials.resolve(m.credentialEnv());
        if (secret == null || secret.isBlank()) {
            log.warn("API MCP server '{}' names credentialEnv '{}', which resolves to nothing — "
                    + "leaving its stored credential as it is.", m.id(), m.credentialEnv());
            return;
        }
        replaceApiMcpCredential.handle(new ReplaceApiMcpCredentialCommand(m.id(), secret));
    }

    /**
     * A file that does not mention tools leaves the offer alone; one that lists none empties it.
     *
     * <p>The asymmetry is deliberate and matches {@code credentialEnv} directly above. Absent is
     * "I am not saying anything about this", and wiping an offer somebody composed because a base
     * url was edited in a hurry is not what that file asked for. An explicit empty list IS the repo
     * saying so, and leaves the entry catalogued and visibly unusable.
     */
    private void exposeTools(ApiMcpManifest m) {
        if (m.tools() == null) {
            return;
        }
        exposeApiTools.handle(new ExposeApiToolsCommand(m.id(), m.tools().stream()
                .map(t -> new ExposeApiToolsCommand.Tool(t.operation(), t.toolName(),
                        t.description(), t.requiredRoles()))
                .toList()));
    }

    private void upsertRag(RagManifest m, Report report) {
        guardAndRun(RAG, m.id(), () -> ragQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            if (exists) {
                updateRag.handle(new UpdateRagCommand(m.id(), name, m.store(), m.connectionUrl(),
                        m.collection(), m.embeddingLlmId(), m.topK(), m.description(),
                        enabled(m.enabled())));
            } else {
                createRag.handle(new CreateRagCommand(m.id(), name, m.store(), m.connectionUrl(),
                        m.collection(), m.embeddingLlmId(), m.topK(), m.description()));
                if (!enabled(m.enabled())) {
                    updateRag.handle(new UpdateRagCommand(m.id(), name, m.store(), m.connectionUrl(),
                            m.collection(), m.embeddingLlmId(), m.topK(), m.description(), false));
                }
            }
        });
    }

    private void upsertAgent(AgentManifest m, Report report) {
        guardAndRun(AGENT, m.id(), () -> agentQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            var mcpIds = m.mcp() == null ? List.<String>of() : m.mcp();
            var ragIds = m.rag() == null ? List.<String>of() : m.rag();
            var peerIds = m.peers() == null ? List.<String>of() : m.peers();
            if (exists) {
                updateAgent.handle(new UpdateAgentCommand(m.id(), name, m.systemPrompt(), m.llm(),
                        mcpIds, ragIds, peerIds, m.description(), enabled(m.enabled())));
            } else {
                createAgent.handle(new CreateAgentCommand(m.id(), name, m.systemPrompt(), m.llm(),
                        mcpIds, ragIds, peerIds, m.description()));
                if (!enabled(m.enabled())) {
                    updateAgent.handle(new UpdateAgentCommand(m.id(), name, m.systemPrompt(), m.llm(),
                            mcpIds, ragIds, peerIds, m.description(), false));
                }
            }
        });
    }

    private void upsertBudget(BudgetManifest m, Report report) {
        guardAndRun(BUDGET, m.id(), () -> budgetQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            if (exists) {
                updateBudget.handle(new UpdateBudgetCommand(m.id(), name, m.scope(), m.subject(),
                        m.period(), m.limitTokens(), enabled(m.enabled())));
            } else {
                createBudget.handle(new CreateBudgetCommand(m.id(), name, m.scope(), m.subject(),
                        m.period(), m.limitTokens()));
                if (!enabled(m.enabled())) {
                    updateBudget.handle(new UpdateBudgetCommand(m.id(), name, m.scope(), m.subject(),
                            m.period(), m.limitTokens(), false));
                }
            }
        });
    }

    private void upsertRoute(RouteManifest m, Report report) {
        guardAndRun(ROUTE, m.id(), () -> routeQuery.getById(m.id()).isPresent(), report, exists -> {
            var name = orId(m.name(), m.id());
            var priority = m.priority() == null ? 100 : m.priority();
            var input = m.inputGuardrails() == null ? List.<String>of() : m.inputGuardrails();
            var output = m.outputGuardrails() == null ? List.<String>of() : m.outputGuardrails();
            if (exists) {
                updateRoute.handle(new UpdateRouteCommand(m.id(), name, priority, m.role(),
                        m.tenant(), m.locale(), m.routePrefix(), m.channel(), m.targetAgent(), input, output,
                        m.guardrailFailure(), enabled(m.enabled())));
            } else {
                createRoute.handle(new CreateRouteCommand(m.id(), name, priority, m.role(),
                        m.tenant(), m.locale(), m.routePrefix(), m.channel(), m.targetAgent(), input, output,
                        m.guardrailFailure()));
                if (!enabled(m.enabled())) {
                    updateRoute.handle(new UpdateRouteCommand(m.id(), name, priority, m.role(),
                            m.tenant(), m.locale(), m.routePrefix(), m.channel(), m.targetAgent(), input, output,
                            m.guardrailFailure(), false));
                }
            }
        });
    }

    /**
     * The provenance guard shared by every upsert: refuse to touch an id the console owns, run the
     * given upsert otherwise, and record the entry as git-managed on success. {@code body} takes
     * whether the entry already existed, so it can choose create vs update.
     */
    private void guardAndRun(String kind, String id, Supplier<Boolean> existsCheck, Report report,
                             java.util.function.Consumer<Boolean> body) {
        try {
            boolean exists = existsCheck.get();
            if (exists && !registry.isManaged(kind, id)) {
                log.warn("{} '{}' exists and was created in the console — GitOps leaves it alone "
                        + "rather than overwrite it. Delete it in the console to let the repo own it.",
                        kind, id);
                report.skipped++;
                return;
            }
            body.accept(exists);
            registry.markManaged(kind, id);
            if (exists) {
                report.updated++;
            } else {
                report.created++;
            }
        } catch (Exception e) {
            log.error("GitOps could not reconcile {} '{}': {}", kind, id, e.toString());
            report.failed++;
        }
    }

    // ── deletes ──────────────────────────────────────────────────────────────────────────────

    /**
     * Delete every git-managed id of {@code kind} that the repo no longer declares. A managed id
     * whose entry is already gone from the catalogue (a console delete of a git entry) is just
     * unregistered. Console-owned ids never reach here — they were never registered.
     */
    private void deleteRemoved(String kind, Set<String> desiredIds,
                               java.util.function.Predicate<String> stillPresent,
                               java.util.function.Consumer<String> delete, Report report) {
        for (var id : Set.copyOf(registry.managedIds(kind))) {
            if (desiredIds.contains(id)) {
                continue;
            }
            try {
                if (stillPresent.test(id)) {
                    delete.accept(id);
                    report.deleted++;
                }
                registry.unmark(kind, id);
            } catch (Exception e) {
                log.error("GitOps could not delete removed {} '{}': {}", kind, id, e.toString());
                report.failed++;
            }
        }
    }

    private static <T> Set<String> ids(List<T> manifests, java.util.function.Function<T, String> id) {
        return manifests.stream().map(id).collect(Collectors.toSet());
    }

    private static boolean enabled(Boolean value) {
        return value == null || value;
    }

    private static String orId(String name, String id) {
        return name == null || name.isBlank() ? id : name;
    }
}
