package io.mateu.ecdemo1.iacp.infra.in.ui.pages.agent;

import io.mateu.ecdemo1.iacp.infra.in.ui.pages.CatalogueReferenceOptions;
import io.mateu.ecdemo1.uicommons.crud.CatalogueEditor;
import io.mateu.ecdemo1.iacp.application.out.query.dto.AgentDto;
import io.mateu.ecdemo1.iacp.application.usecases.agent.ResolveAgentConfigUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentCommand;
import io.mateu.ecdemo1.iacp.application.usecases.agent.create.CreateAgentUseCase;
import io.mateu.ecdemo1.iacp.application.usecases.agent.update.UpdateAgentCommand;
import io.mateu.ecdemo1.iacp.application.usecases.agent.update.UpdateAgentUseCase;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.Multiline;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Option;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.State;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.OptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The composition: a prompt, one model, and the servers and sources it may reach.
 *
 * <p>The references are picked from the catalogues ({@link CatalogueReferenceOptions}), so a typo can
 * no longer make one up. What picking cannot prevent is an entry being disabled or deleted after
 * the agent was composed from it — {@code ResolveAgentConfigUseCase} drops those at read time with
 * a warning, and "Preview resolved configuration" is the button that surfaces that before a user
 * does.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class AgentViewModel implements CatalogueEditor<AgentDto>, OptionsSupplier {

    @Section("Agent")
    @ReadOnly
    @HiddenInCreate
    String id;

    @HiddenInList
    @Help("Only used when creating. This is the id a running service asks for — renaming it "
            + "later would take that service's chat panel down.")
    String newId;

    @NotEmpty
    String name;

    @Multiline
    String description;

    @Section("Model")
    @NotEmpty
    @Lookup(search = CatalogueReferenceOptions.class, label = CatalogueReferenceOptions.class)
    @Label("LLM")
    @Help("An LLM from the LLM catalogue. Unlike a missing tool, a missing model leaves "
            + "nothing to answer with, so an agent is not served without a usable one.")
    String llmId;

    @Section("Instructions")
    @NotEmpty
    @Multiline
    @Help("Everything the model is told before anything a user says. It decides whether the "
            + "agent reports a failed tool call or invents an answer around it.")
    String systemPrompt;

    @Multiline
    @Help("Optional JSON output schema. Required for agents published for workflow execution. Console edits do not publish workflow capabilities.")
    String outputSchema;

    @Section("Tools and sources")
    @Stereotype(FieldStereotype.checkbox)
    @Label("MCP servers")
    @Help("MCP servers the agent may call. Disabled ones are dropped when the configuration is "
            + "served, and reported — they do not stop the agent.")
    List<String> mcpIds;

    @Stereotype(FieldStereotype.checkbox)
    @Label("RAG sources")
    @Help("RAG sources the agent may search. Same handling as the MCP servers above.")
    List<String> ragIds;

    @Section("Other agents")
    @Stereotype(FieldStereotype.checkbox)
    @Label("Agents it may call (A2A)")
    @Help("Each one is offered to this agent's model as a tool that sends it a request over A2A "
            + "and returns its answer. Its description is what the model reads to decide when to "
            + "delegate. Disabled or deleted ones are dropped when the configuration is served.")
    List<String> peerAgentIds;

    @Section("Status")
    boolean enabled;

    @ReadOnly
    @HiddenInList
    @Multiline
    @Help("Result of the last preview in this session. Not stored.")
    String lastPreview;

    final CreateAgentUseCase createAgentUseCase;
    final UpdateAgentUseCase updateAgentUseCase;
    final ResolveAgentConfigUseCase resolveAgentConfigUseCase;
    final CatalogueReferenceOptions referenceOptions;

    public String create(HttpRequest httpRequest) {
        return createAgentUseCase.handle(new CreateAgentCommand(newId, name, systemPrompt, llmId,
                mcpIds, ragIds, peerAgentIds, description, io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments.readMap(outputSchema)));
    }

    public void save(HttpRequest httpRequest) {
        updateAgentUseCase.handle(new UpdateAgentCommand(id, name, systemPrompt, llmId,
                mcpIds, ragIds, peerAgentIds, description, enabled, io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments.readMap(outputSchema)));
    }

    /**
     * Resolves this agent exactly as a running service would, and shows what came back — minus the
     * credential, which is the one field the preview must not print. What it is really for is the
     * warnings: a dropped MCP server is invisible in the catalogue and obvious here.
     */
    @Toolbar
    @Action(idempotent = true)
    public Object previewResolvedConfiguration(HttpRequest httpRequest) {
        try {
            var resolved = resolveAgentConfigUseCase.handle(id);
            var sb = new StringBuilder();
            sb.append("LLM: ").append(resolved.llm().name())
                    .append(" (").append(resolved.llm().provider()).append('/')
                    .append(resolved.llm().model()).append(")\n");
            sb.append("MCP servers served: ").append(resolved.mcps().size()).append('\n');
            resolved.mcps().forEach(m -> sb.append("  - ").append(m.name())
                    .append(" ").append(m.url()).append('\n'));
            sb.append("RAG sources served: ").append(resolved.rags().size()).append('\n');
            resolved.rags().forEach(r -> sb.append("  - ").append(r.name())
                    .append(" / ").append(r.collection()).append('\n'));
            sb.append("Peer agents (A2A): ").append(resolved.peers().size()).append('\n');
            resolved.peers().forEach(p -> sb.append("  - ").append(p.name())
                    .append(" ").append(p.a2aUrl()).append('\n'));
            if (resolved.warnings().isEmpty()) {
                sb.append("No warnings.");
            } else {
                sb.append("Warnings:\n");
                resolved.warnings().forEach(w -> sb.append("  ! ").append(w).append('\n'));
            }
            lastPreview = sb.toString();
        } catch (ResolveAgentConfigUseCase.AgentNotUsableException e) {
            lastPreview = "Would not be served: " + e.getMessage();
            return List.of(Message.error(lastPreview), new State(this));
        }
        return List.of(new Message(lastPreview), new State(this));
    }

    /**
     * The checkboxes' options: every MCP server and RAG source, and every agent but this one — see
     * {@link CatalogueReferenceOptions}.
     */
    @Override
    public List<Option> options(String fieldName, HttpRequest httpRequest) {
        return switch (fieldName) {
            case "mcpIds", "ragIds" -> referenceOptions.all(fieldName, httpRequest);
            case "peerAgentIds" -> referenceOptions.all(fieldName, httpRequest).stream()
                    .filter(o -> id == null || !id.equals(o.value()))
                    .toList();
            default -> List.of();
        };
    }

    @Override
    public String id() {
        return id;
    }

    public AgentViewModel load(AgentDto dto) {
        id = dto.id();
        newId = dto.id();
        name = dto.name();
        description = dto.description();
        llmId = dto.llmId();
        systemPrompt = dto.systemPrompt();
        mcpIds = new ArrayList<>(dto.mcpIds());
        ragIds = new ArrayList<>(dto.ragIds());
        peerAgentIds = new ArrayList<>(dto.peerAgentIds());
        enabled = dto.enabled();
        outputSchema = io.mateu.ecdemo1.iacp.infra.out.persistence.JsonDocuments.write(dto.outputSchema());
        lastPreview = null;
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New agent";
    }
}
