package io.mateu.ecdemo1.iaagent.workflow.infra;

import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.ToolOutcome;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StrictWorkflowToolsTest {
    McpSchema.CallToolResult failed(String text){return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).isError(true).build();}
    McpError rpc(int code){return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code,"bad\n\tat Secret.trace()"));}
    @Test void readOnlyIsErrorReturnsContentToModel(){var outcome=(ToolOutcome.Rejected)StrictWorkflowTools.isError(failed("room not found"),true,false);assertTrue(outcome.message().contains("room not found"));assertFalse(outcome.transientFailure());}
    @Test void effectfulIsErrorHoldsUnlessMcpOptsIn(){assertThrows(IllegalStateException.class,()->StrictWorkflowTools.isError(failed("x"),false,false));assertInstanceOf(ToolOutcome.Rejected.class,StrictWorkflowTools.isError(failed("x"),false,true));}
    @Test void preExecutionRpcErrorIsRejectedAndSanitized(){var outcome=(ToolOutcome.Rejected)StrictWorkflowTools.rpcError(rpc(-32602),false,false);assertEquals("-32602",outcome.code());assertEquals("bad",outcome.message());}
    @Test void effectfulInternalRpcErrorStaysUncertain(){assertThrows(McpError.class,()->StrictWorkflowTools.rpcError(rpc(-32603),false,false));assertInstanceOf(ToolOutcome.Rejected.class,StrictWorkflowTools.rpcError(rpc(-32603),true,false));}
    @Test void sanitizeTruncatesLongMessages(){assertTrue(StrictWorkflowTools.sanitize("a".repeat(900)).length()<=503);}
}
