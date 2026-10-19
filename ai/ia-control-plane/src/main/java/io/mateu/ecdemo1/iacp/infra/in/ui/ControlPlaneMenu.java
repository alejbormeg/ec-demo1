package io.mateu.ecdemo1.iacp.infra.in.ui;

import io.mateu.ecdemo1.iacp.infra.in.ui.pages.agent.AgentCrudOrchestrator;
import io.mateu.ecdemo1.iacp.infra.in.ui.pages.apimcp.ApiMcpCrudOrchestrator;
import io.mateu.ecdemo1.iacp.infra.in.ui.pages.budget.BudgetCrudOrchestrator;
import io.mateu.ecdemo1.iacp.infra.in.ui.pages.llm.LlmCrudOrchestrator;
import io.mateu.ecdemo1.iacp.infra.in.ui.pages.mcp.McpCrudOrchestrator;
import io.mateu.ecdemo1.iacp.infra.in.ui.pages.rag.RagCrudOrchestrator;
import io.mateu.ecdemo1.iacp.infra.in.ui.pages.route.RouteCrudOrchestrator;
import io.mateu.uidl.annotations.Menu;

/**
 * Agents first, then the three catalogues an agent is composed from.
 *
 * <p>The order is the reading order: an agent is the thing anyone came here to change, and the
 * other three are what it is made of. Alphabetical would put the parts before the whole.
 */
public class ControlPlaneMenu {

    @Menu
    AgentCrudOrchestrator agents;

    @Menu
    io.mateu.ecdemo1.iacp.infra.in.ui.pages.workflow.WorkflowExecutionsPage workflowAgentExecutions;

    @Menu
    LlmCrudOrchestrator llms;

    @Menu
    McpCrudOrchestrator mcpServers;

    /**
     * APIs offered as MCP servers. Beside the catalogue of servers somebody else runs rather than
     * inside it: an entry here owns its tool list, because the offer is composed on this screen,
     * and an Mcp deliberately owns none — see the two aggregates.
     */
    @Menu
    ApiMcpCrudOrchestrator apiMcpServers;

    @Menu
    RagCrudOrchestrator ragSources;

    @Menu
    BudgetCrudOrchestrator budgets;

    @Menu
    RouteCrudOrchestrator routes;

}
