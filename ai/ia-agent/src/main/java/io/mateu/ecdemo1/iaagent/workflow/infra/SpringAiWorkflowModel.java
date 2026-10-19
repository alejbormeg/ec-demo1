package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.config.ChatClientRegistry;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.Model;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;

/** ChatModel performs exactly one provider request. Only the application dispatches tools. */
public final class SpringAiWorkflowModel implements Model {
    private final ChatClientRegistry registry;private final HttpWorkflowCatalogue catalogue;private final ObjectMapper mapper;
    private final io.micrometer.observation.ObservationRegistry observations;
    private final io.mateu.ecdemo1.iaagent.observability.TraceHeaders traces;
    public SpringAiWorkflowModel(ChatClientRegistry registry,HttpWorkflowCatalogue catalogue,ObjectMapper mapper){this(registry,catalogue,mapper,io.micrometer.observation.ObservationRegistry.NOOP,null);}
    public SpringAiWorkflowModel(ChatClientRegistry registry,HttpWorkflowCatalogue catalogue,ObjectMapper mapper,io.micrometer.observation.ObservationRegistry observations,io.mateu.ecdemo1.iaagent.observability.TraceHeaders traces){this.registry=registry;this.catalogue=catalogue;this.mapper=mapper.copy().findAndRegisterModules();this.observations=observations;this.traces=traces;}
    @Override public Round call(String rootId,String agentId,List<io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.Message> history,List<ToolSpec> tools,long deadlineEpochMillis) {
        return io.micrometer.observation.Observation.createNotStarted("ia.workflow.round",observations)
                .lowCardinalityKeyValue("agent.id",agentId).highCardinalityKeyValue("workflow.invocation.id",rootId)
                .observe(()->callProvider(rootId,agentId,history,tools,deadlineEpochMillis));
    }
    private Round callProvider(String rootId,String agentId,List<io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.Message> history,List<ToolSpec> tools,long deadlineEpochMillis) {
        var config=catalogue.config(rootId,agentId).config();
        var options=registry.optionsFor(config.llm()).build();
        if(!(options instanceof ToolCallingChatOptions toolOptions))throw new IllegalStateException("Provider does not support tools");
        var callbacks=tools.stream().map(spec->(ToolCallback)new ToolCallback(){
            @Override public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(spec.name()).description(spec.description()).inputSchema(spec.inputSchema()).build();}
            @Override public String call(String input){throw new IllegalStateException("Model must never dispatch tools directly");}
        }).toList();
        var remaining=java.time.Duration.ofMillis(Math.max(1,deadlineEpochMillis-System.currentTimeMillis()));
        if(options instanceof org.springframework.ai.openai.OpenAiChatOptions openai)
            options=openai.mutate().timeout(remaining).maxRetries(0).toolCallbacks(callbacks).build();
        else if(options instanceof org.springframework.ai.anthropic.AnthropicChatOptions anthropic)
            options=anthropic.mutate().timeout(remaining).maxRetries(0).toolCallbacks(callbacks).build();
        else throw new IllegalArgumentException("Unsupported workflow model provider");
        var messages=history.stream().map(this::message).toList();
        var response=registry.modelFor(config.llm()).call(new Prompt(messages,options));
        var output=response.getResult().getOutput();
        var calls=output.getToolCalls().stream().map(c->new ToolCall(c.id(),c.name(),c.arguments())).toList();
        Map<String,Object> metadata=mapper.convertValue(output.getMetadata(),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        // Provider extensions (including thought signatures) live in assistant metadata and survive replay.
        var assistant=new io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.Message("assistant",output.getText(),metadata,calls,null,null);
        var usage=response.getMetadata().getUsage();
        String raw;
        try{raw=mapper.writeValueAsString(response);}catch(Exception ex){throw new IllegalStateException("Cannot persist complete provider response",ex);}
        var responseMetadata=new LinkedHashMap<String,Object>();responseMetadata.put("generation",mapper.convertValue(response.getResult().getMetadata(),Object.class));responseMetadata.put("response",mapper.convertValue(response.getMetadata(),Object.class));if(traces!=null)responseMetadata.put("trace",traces.current());
        return new Round(assistant,raw,usage==null?0:Objects.requireNonNullElse(usage.getPromptTokens(),0),usage==null?0:Objects.requireNonNullElse(usage.getCompletionTokens(),0),config.llm().model(),responseMetadata);
    }
    private org.springframework.ai.chat.messages.Message message(io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.Message m) {
        return switch(m.role()){
            case "system"->new SystemMessage(m.text());
            case "user"->new UserMessage(m.text());
            case "assistant"->AssistantMessage.builder().content(m.text()).properties(m.metadata()).toolCalls(m.calls().stream().map(c->new AssistantMessage.ToolCall(c.id(),"function",c.name(),c.arguments())).toList()).build();
            case "tool"->ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(m.toolCallId(),m.toolName(),m.text()))).build();
            default->throw new IllegalArgumentException("Unsupported persisted message role");
        };
    }
}
