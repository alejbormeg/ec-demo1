package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.config.AgentConfig;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Chat's lenient MCP policy is deliberately not used by durable workflow execution. */
public final class StrictWorkflowTools implements ToolFactory {
    private final HttpWorkflowCatalogue catalogue;private final WorkflowIdentity identity;private final ObjectMapper mapper;
    private static final String MESSAGE_SCHEMA="{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}";
    public StrictWorkflowTools(HttpWorkflowCatalogue catalogue,WorkflowIdentity identity,ObjectMapper mapper){this.catalogue=catalogue;this.identity=identity;this.mapper=mapper;}
    @Override public Tools open(Request request) {
        var config=catalogue.config(request.rootInvocationId(),request.agentRef()).config();
        validateSelection(request.mcp(),config.mcps()==null?List.of():config.mcps().stream().map(AgentConfig.Mcp::id).toList());
        validateSelection(request.rag(),config.rags()==null?List.of():config.rags().stream().map(AgentConfig.Rag::id).toList());
        validateSelection(request.peers(),config.peersOrEmpty().stream().map(AgentConfig.Peer::id).toList());
        var opened=new Opened(request);
        try {
            for(var mcp:config.mcps()==null?List.<AgentConfig.Mcp>of():config.mcps()) if(selected(request.mcp(),mcp.id())) {
                if(!Set.of("SSE","STREAMABLE_HTTP").contains(mcp.transport().toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("Unsupported workflow MCP transport "+mcp.transport()+" for "+mcp.id()+"; supported: SSE, STREAMABLE_HTTP");
                if(mcp.timeoutSeconds()<=0)throw new IllegalArgumentException("MCP timeout must be positive");
                long remaining=Math.max(1,request.deadlineEpochMillis()-System.currentTimeMillis());
                var duration=Duration.ofMillis(Math.min(Duration.ofSeconds(mcp.timeoutSeconds()).toMillis(),remaining));
                var endpointUri=URI.create(mcp.url());
                if(!Set.of("http","https").contains(endpointUri.getScheme())||endpointUri.getUserInfo()!=null||endpointUri.getQuery()!=null||endpointUri.getFragment()!=null)throw new IllegalArgumentException("MCP URL must be an HTTP endpoint without credentials/query/fragment");
                var origin=endpointUri.getScheme()+"://"+endpointUri.getRawAuthority();
                var path=endpointUri.getRawPath();
                var endpoint=path==null||path.isBlank()||"/".equals(path)?("SSE".equalsIgnoreCase(mcp.transport())?"/sse":"/mcp"):path;
                io.modelcontextprotocol.spec.McpClientTransport transport;
                if("SSE".equalsIgnoreCase(mcp.transport())) transport=HttpClientSseClientTransport.builder(origin).sseEndpoint(endpoint).connectTimeout(duration).customizeClient(b->b.connectTimeout(duration))
                        .httpRequestCustomizer((b,method,uri,body,ctx)->b.header("Authorization",identity.authorization())).build();
                else transport=HttpClientStreamableHttpTransport.builder(origin).endpoint(endpoint).connectTimeout(duration).customizeClient(b->b.connectTimeout(duration))
                        .httpRequestCustomizer((b,method,uri,body,ctx)->b.header("Authorization",identity.authorization())).build();
                var client=McpClient.sync(transport).requestTimeout(duration).clientInfo(new McpSchema.Implementation(toolName(mcp.id()),"1.0")).build();
                opened.clients.add(client);client.initialize();
                for(var tool:client.listTools().tools()) {
                    var name=toolName(mcp.id())+"_"+tool.name();
                    boolean readOnly=tool.annotations()!=null&&Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                    String inputSchema;try{inputSchema=mapper.writeValueAsString(tool.inputSchema());}catch(Exception ex){throw new IllegalArgumentException("Invalid MCP schema",ex);}
                    opened.add(new ToolSpec(name,Objects.toString(tool.description(),tool.name()),inputSchema,"mcp",mcp.id(),readOnly),null);
                    opened.mcpCalls.put(name,new BoundMcp(client,tool.name(),mcp.returnToolErrorsToModel()));
                }
                try {
                    if(client.listPrompts().prompts().stream().anyMatch(p->"system-context".equals(p.name()))) {
                        var prompt=client.getPrompt(new McpSchema.GetPromptRequest("system-context",Map.of()));
                        for(var msg:prompt.messages())if(msg.content() instanceof McpSchema.TextContent text)opened.contexts.add(text.text());
                    }
                }catch(Exception ignored){/* Prompts are optional MCP capability, not an omitted tool. */}
            }
            for(var rag:config.rags()==null?List.<AgentConfig.Rag>of():config.rags())if(selected(request.rag(),rag.id())) {
                var spec=new ToolSpec("search_"+toolName(rag.id()),"Search "+rag.name()+". "+Objects.toString(rag.description(),""),"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}","rag",rag.id(),true);
                opened.add(spec,null);opened.rags.put(rag.id(),rag);
            }
            if(request.chain().size()<2)for(var peer:config.peersOrEmpty())if(selected(request.peers(),peer.id())&&!request.chain().contains(peer.id())&&!request.agentRef().equals(peer.id()))
                opened.add(new ToolSpec("ask_"+toolName(peer.id()),Objects.toString(peer.description(),"Delegate to "+peer.name()),MESSAGE_SCHEMA,"a2a",peer.id(),false),null);
            return opened;
        }catch(RuntimeException ex){opened.close();throw ex;}
    }
    private String toolName(String id){return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_");}
    private boolean selected(List<String> selection,String id){return selection==null||selection.contains(id);}
    private void validateSelection(List<String> selected,List<String> allowed){if(selected!=null&&!allowed.containsAll(selected))throw new IllegalArgumentException("Capability selection exceeds frozen agent permissions");}
    private record BoundMcp(McpSyncClient client,String remoteName,boolean returnErrors) {}
    /** JSON-RPC codes the server raises before executing the tool. */
    private static final Set<Integer> PRE_EXECUTION=Set.of(-32700,-32600,-32601,-32602);
    /** Server text reaches the model: keep the first line only and bound its size. */
    static String sanitize(String message){var text=Objects.toString(message,"").strip();int nl=text.indexOf('\n');if(nl>=0)text=text.substring(0,nl);return text.length()>500?text.substring(0,500)+"...":text;}
    static ToolOutcome isError(McpSchema.CallToolResult result,boolean readOnly,boolean returnErrors) {
        if(!readOnly&&!returnErrors)throw new IllegalStateException("MCP tool returned isError=true");
        var text=new StringBuilder();if(result.content()!=null)for(var c:result.content())if(c instanceof McpSchema.TextContent t)text.append(t.text()).append(' ');
        return new ToolOutcome.Rejected(sanitize(text.isEmpty()?"MCP tool returned isError=true":text.toString()),"tool_error",false);
    }
    static ToolOutcome rpcError(McpError error,boolean readOnly,boolean returnErrors) {
        var rpc=error.getJsonRpcError();
        if(rpc==null||!(readOnly||returnErrors||PRE_EXECUTION.contains(rpc.code())))throw error;
        return new ToolOutcome.Rejected(sanitize(rpc.message()),String.valueOf(rpc.code()),false);
    }
    private final class Opened implements Tools {
        private final Request request;private final List<McpSyncClient> clients=new ArrayList<>();private final List<String> contexts=new ArrayList<>();
        private final Map<String,ToolSpec> definitions=new LinkedHashMap<>();private final Map<String,ToolCallback> callbacks=new HashMap<>();private final Map<String,AgentConfig.Rag> rags=new HashMap<>();private final Map<String,BoundMcp> mcpCalls=new HashMap<>();
        private Opened(Request request){this.request=request;}
        private void add(ToolSpec spec,ToolCallback cb){if(definitions.putIfAbsent(spec.name(),spec)!=null)throw new IllegalArgumentException("Duplicate workflow tool name: "+spec.name());if(cb!=null)callbacks.put(spec.name(),cb);}
        @Override public List<ToolSpec> definitions(){return List.copyOf(definitions.values());}
        @Override public String systemContext(){return String.join("\n",contexts);}
        @Override public ToolOutcome call(ToolSpec spec,String args,String idempotencyKey){
            Map<String,Object> arguments;try{arguments=mapper.readValue(args,new TypeReference<Map<String,Object>>(){});}catch(Exception ex){return new ToolOutcome.Rejected("Arguments must be a JSON object","invalid_arguments",false);}
            if("rag".equals(spec.kind()))try{return new ToolOutcome.Ok(mapper.writeValueAsString(catalogue.rag(request,spec.sourceId(),arguments,rags.get(spec.sourceId()).topK())));}catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException(ex);}
            var binding=mcpCalls.get(spec.name());if(binding==null)return new ToolOutcome.Rejected("Tool unavailable","unregistered_tool",false);
            try {
                var result=binding.client().callTool(new McpSchema.CallToolRequest(binding.remoteName(),arguments,Map.of("workflow.idempotencyKey",idempotencyKey)));
                if(Boolean.TRUE.equals(result.isError()))return isError(result,spec.readOnly(),binding.returnErrors());
                return new ToolOutcome.Ok(mapper.writeValueAsString(result));
            }catch(McpError ex){return rpcError(ex,spec.readOnly(),binding.returnErrors());}
            catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("MCP tool failed",ex);}

        }
        @Override public void close(){for(var client:clients)try{client.close();}catch(Exception ignored){}}
    }
}
