package io.mateu.ecdemo1.iacp.domain.aggregates.agent;

import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.AgentId;
import io.mateu.ecdemo1.iacp.domain.aggregates.agent.vo.SystemPrompt;
import io.mateu.ecdemo1.iacp.domain.aggregates.llm.vo.LlmId;
import io.mateu.ecdemo1.iacp.domain.aggregates.mcp.vo.McpId;
import io.mateu.ecdemo1.iacp.domain.aggregates.rag.vo.RagId;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Enabled;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Name;
import io.mateu.ecdemo1.iacp.domain.aggregates.shared.vo.Time;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What the other three catalogues are for: a prompt, one model, and the tools and sources it may
 * reach. An agent is the only thing here a running service is ever handed.
 *
 * <p>It refers to the other aggregates by id and holds nothing of them. That is the point of the
 * split — an MCP server's URL changes in one place, a model's key is rotated in one place, and
 * every agent composed from them follows without being touched.
 *
 * <p><strong>The references are not validated here, and that is deliberate.</strong> An aggregate
 * cannot see the other catalogues without becoming a query, and a rule enforced at write time
 * would only hold at write time anyway: nothing stops an LLM being disabled, or an MCP server
 * deleted, an hour after an agent was composed from it. So the invariant this design commits to is
 * the one that can actually be kept — {@code resolve} at read time, which drops what is no longer
 * usable and reports what it dropped. Write-time validation is a convenience on top of that (the
 * editor does it, to catch typos), never the guarantee.
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Agent extends AggregateRoot {

    AgentId id;
    Name name;
    SystemPrompt systemPrompt;
    LlmId llmId;
    List<McpId> mcpIds;
    List<RagId> ragIds;
    /**
     * The other agents this one may call over A2A, each offered to its model as one tool. Never
     * itself: an agent that delegates to itself is a loop with extra steps, and a model given the
     * option will take it.
     */
    List<AgentId> peerAgentIds;
    String description;
    Enabled enabled;
    Time created;
    Map<String, Object> outputSchema;

    public Agent(AgentId id, Name name, SystemPrompt systemPrompt, LlmId llmId,
                 List<McpId> mcpIds, List<RagId> ragIds, List<AgentId> peerAgentIds,
                 String description, Enabled enabled, Time created) {
        this(id, name, systemPrompt, llmId, mcpIds, ragIds, peerAgentIds, description, enabled, created, null);
    }

    public void defineOutputSchema(Map<String, Object> schema) {
        this.outputSchema = schema == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(schema));
    }

    public static Agent of(AgentId id, Name name, SystemPrompt systemPrompt, LlmId llmId,
                           List<McpId> mcpIds, List<RagId> ragIds, List<AgentId> peerAgentIds,
                           String description) {
        var agent = new Agent();
        agent.id = id;
        agent.name = name;
        agent.systemPrompt = systemPrompt;
        agent.llmId = llmId;
        agent.mcpIds = dedupe(mcpIds);
        agent.ragIds = dedupe(ragIds);
        agent.peerAgentIds = peers(id, peerAgentIds);
        agent.description = description;
        agent.enabled = Enabled.yes();
        agent.created = new Time(LocalDateTime.now());
        return agent;
    }

    public void update(Name name, SystemPrompt systemPrompt, LlmId llmId,
                       List<McpId> mcpIds, List<RagId> ragIds, List<AgentId> peerAgentIds,
                       String description, Enabled enabled) {
        // First, so a refused update leaves nothing half-applied.
        var peers = peers(id, peerAgentIds);
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.llmId = llmId;
        this.mcpIds = dedupe(mcpIds);
        this.ragIds = dedupe(ragIds);
        this.peerAgentIds = peers;
        this.description = description;
        this.enabled = enabled;
    }

    /**
     * Same id twice is the same server twice, and an agent handed the same MCP endpoint twice gets
     * every one of its tools twice — which Spring AI rejects outright with "Multiple tools with the
     * same name", failing the whole prompt rather than the duplicate. Cheap to prevent here;
     * confusing to diagnose there.
     */
    private static <T> List<T> dedupe(List<T> ids) {
        return ids == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /**
     * Refused rather than silently dropped: the editor never offers the agent itself, so reaching
     * this is a hand-written manifest or a direct API call, and whoever wrote it should hear so.
     */
    private static List<AgentId> peers(AgentId self, List<AgentId> peerAgentIds) {
        var peers = dedupe(peerAgentIds);
        if (peers.contains(self)) {
            throw new IllegalArgumentException("Agent '" + self + "' cannot call itself over A2A");
        }
        return peers;
    }

    public List<AgentId> getPeerAgentIds() {
        return peerAgentIds == null ? List.of() : peerAgentIds;
    }

    public boolean isUsable() {
        return enabled.value();
    }
}
