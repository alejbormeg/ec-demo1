package io.mateu.ecdemo1.iacp.infra.in.ui.pages.workflow;

import io.mateu.ecdemo1.iacp.infra.out.workflow.WorkflowRuntimeClient;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.OptionsSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** Form state travels with the UI; execution state stays in the workflow runtime's database. */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Title("Workflow agent executions")
@Trigger(type = TriggerType.OnLoad, actionId = "refresh")
public class WorkflowExecutionsPage implements OptionsSupplier {
    final WorkflowRuntimeClient runtime;
    @Section("Execution overview") @ReadOnly @Multiline
    String overview;
    @Stereotype(FieldStereotype.select)
    @Help("Select an invocation, then load it to inspect its frozen configuration revision, token usage and delegation chain.")
    String executionId;
    @ReadOnly @Multiline
    String details;
    @ReadOnly @Multiline
    String traces;
    @Section("Review an uncertain tool call")
    @Help("Choose the tool call after loading an execution in REVIEW_REQUIRED.")
    @Stereotype(FieldStereotype.select)
    String toolCallId;
    @ReadOnly @Multiline
    String uncertainCalls;
    @ReadOnly
    String requestDigest;
    @ReadOnly
    String toolDigest;
    @Multiline
    @Help("The result independently confirmed in the target system. This result is persisted before execution resumes.")
    String confirmedResult;
    @Multiline
    @Help("Evidence of reconciliation, such as a target-system transaction identifier and the verification performed.")
    String evidence;

    @Toolbar @Action(idempotent = true)
    public Object refresh(HttpRequest request) {
        var summaries = runtime.list(bearer(request));
        overview = summaries.isEmpty() ? "No workflow agent executions." : summaries.stream()
                .map(s -> s.id() + " | " + s.agentId() + " | " + s.state() + " | tokens "
                        + s.inputTokens() + "/" + s.outputTokens() + " | deadline " + s.deadline())
                .collect(java.util.stream.Collectors.joining("\n"));
        return new State(this);
    }
    @Toolbar @Action(idempotent = true)
    public Object loadExecution(HttpRequest request) {
        var summary = selected(request);
        details = runtime.details(executionId, bearer(request));
        requestDigest = summary.requestDigest();
        traces = WorkflowRuntimeClient.pretty(summary.traces() == null ? List.of() : summary.traces());
        uncertainCalls = WorkflowRuntimeClient.pretty(summary.unknownCalls() == null ? List.of() : summary.unknownCalls());
        toolCallId = null; toolDigest = null; confirmedResult = null; evidence = null;
        return new State(this);
    }
    @Toolbar @Action(idempotent = true)
    public Object loadToolCall(HttpRequest request) {
        var summary = selected(request);
        if (!Objects.equals(requestDigest, summary.requestDigest()))
            throw new IllegalArgumentException("Execution changed; load the execution again");
        var call = (summary.unknownCalls() == null ? List.<WorkflowRuntimeClient.UnknownCall>of() : summary.unknownCalls())
                .stream().filter(t -> t.toolCallId().equals(toolCallId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select a pending uncertain tool call"));
        toolDigest = call.toolDigest(); confirmedResult = null; evidence = null;
        return new State(this);
    }
    @Toolbar @Action(confirmationRequired = true, confirmationTitle = "Confirm tool reconciliation?",
            confirmationMessage = "Persist the verified result and evidence for this exact tool input, then allow the agent to continue.")
    public Object confirmReconciliation(HttpRequest request) {
        runtime.review(executionId, new WorkflowRuntimeClient.Review(toolCallId, toolDigest, requestDigest,
                confirmedResult, evidence), bearer(request));
        confirmedResult = null; evidence = null; toolDigest = null; toolCallId = null;
        refresh(request);
        return List.of(new Message("Reconciliation recorded. The execution is queued to continue."), new State(this));
    }
    @Toolbar @Action(confirmationRequired = true, confirmationTitle = "Cancel this execution chain?",
            confirmationMessage = "Request cancellation of the root and its delegations. Uncertain dispatched writes still require reconciliation.")
    public Object cancelExecution(HttpRequest request) {
        runtime.cancel(executionId, bearer(request)); refresh(request);
        return List.of(new Message("Cancellation requested for the execution chain."), new State(this));
    }
    private WorkflowRuntimeClient.Summary selected(HttpRequest request) {
        return runtime.list(bearer(request)).stream().filter(s -> s.id().equals(executionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select an existing workflow execution"));
    }
    @Override public List<Option> options(String fieldName, HttpRequest request) {
        if ("executionId".equals(fieldName)) return runtime.list(bearer(request)).stream()
                .map(s -> new Option(s.id(), s.id() + " / " + s.agentId() + " / " + s.state())).toList();
        if ("toolCallId".equals(fieldName) && executionId != null) {
            var s = selected(request);
            return (s.unknownCalls() == null ? List.<WorkflowRuntimeClient.UnknownCall>of() : s.unknownCalls()).stream()
                    .map(t -> new Option(t.toolCallId(), t.toolName() + " / " + t.toolCallId())).toList();
        }
        return List.of();
    }
    static String bearer(HttpRequest request) { return request == null ? null : request.getHeaderValue("Authorization"); }
}
