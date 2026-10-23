package io.mateu.ecdemo1.iaagent.workflow.domain;

import java.util.*;

/** Durable state only. No transport, Spring or model SDK types enter the application. */
public final class WorkflowExecution {
    public enum Status { QUEUED, RUNNING, REVIEW_REQUIRED, COMPLETED, FAILED, CANCELLED }
    public enum ToolStatus { PREPARED, DISPATCHED, CONFIRMED, REVIEW_REQUIRED }
    public record Request(String invocationId, String rootInvocationId, String parentInvocationId,
                          String taskExecutionId, String processId, String stepId, String agentRef,
                          String taskPrompt, Map<String,Object> input, List<String> mcp,
                          List<String> rag, List<String> peers, String outputVariable,
                          long deadlineEpochMillis, String initiatingActor, List<String> chain, long timeoutMillis) {}
    public record ToolSpec(String name, String description, String inputSchema, String kind,
                           String sourceId, boolean readOnly) {}
    public record ToolCall(String id, String name, String arguments) {}
    public record Message(String role, String text, Map<String,Object> metadata, List<ToolCall> calls,
                          String toolCallId, String toolName) {}
    public record Round(Message assistant, String providerResponse, int inputTokens, int outputTokens,
                        String model, Map<String,Object> metadata) {}
    public static final class Tool {
        public String id, name, arguments, digest, result, kind, sourceId, childId;
        public boolean readOnly;
        /** {@code error}: definitive refusal returned to the model; {@code sent}: reached the remote server. */
        public boolean error, sent;
        public ToolStatus status = ToolStatus.PREPARED;
        public String evidence, reviewer;
        public Tool() {}
    }
    public Request request;
    public String digest, snapshotId, snapshotDigest, catalogueRevision;
    public Status status = Status.QUEUED;
    public List<Message> messages = new ArrayList<>();
    public List<Round> rounds = new ArrayList<>();
    public List<Tool> tools = new ArrayList<>();
    public String result, error;
    public int completedRounds;
    public long stateSequence, effectiveDeadline;
    public boolean reviewResolved;
    public WorkflowExecution() {}
    public WorkflowExecution(Request request, String digest) { this.request = request; this.digest = digest; this.effectiveDeadline=request.deadlineEpochMillis(); }
    public boolean terminal() { return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED; }
}
