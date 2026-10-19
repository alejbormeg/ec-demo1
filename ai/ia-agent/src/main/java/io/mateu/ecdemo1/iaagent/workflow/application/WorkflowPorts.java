package io.mateu.ecdemo1.iaagent.workflow.application;

import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import java.util.*;

public final class WorkflowPorts {
    private WorkflowPorts() {}
    public interface Store {
        WorkflowExecution find(String id);
        /** Insert-if-absent; reject reuse of an id for a different digest. */
        WorkflowExecution create(WorkflowExecution execution);
        void save(WorkflowExecution execution);
        List<String> pendingRoots();
        List<WorkflowExecution> roots();
        List<WorkflowExecution> children(String rootId);
        /** A database session lock held across recovery, model and tool dispatch. */
        AutoCloseable lock(String rootId);
        void outbox(String id, String kind, String payload);
        List<Outbox> pendingOutbox();
        void delivered(String id);
        void requestCancellation(String rootId);
        boolean cancellationRequested(String rootId);
    }
    public record Outbox(String id, String kind, String payload) {}
    public record Agent(String id, String systemPrompt, Map<String,Object> outputSchema) {}
    public record Snapshot(String id, String digest, String revision, Map<String,Agent> agents) {}
    public interface Catalogue {
        Snapshot resolve(Request request, String digest);
        Snapshot snapshot(String rootId);
        void authorize(Request request);
        void usage(String payload);
    }
    public interface Model { Round call(String rootId, String agentId, List<Message> messages, List<ToolSpec> tools, long deadlineEpochMillis); }
    public interface Tools extends AutoCloseable {
        List<ToolSpec> definitions();
        String systemContext();
        String call(ToolSpec tool, String arguments, String idempotencyKey);
        @Override void close();
    }
    public interface ToolFactory { Tools open(Request request); }
    public interface Codec {
        String encode(Object value);
        Map<String,Object> object(String json);
        String digest(Object value);
        void validate(Map<String,Object> schema, String json);
    }
    public static class DelegationUnavailable extends RuntimeException { public DelegationUnavailable(String message){super(message);} public DelegationUnavailable(String message,Throwable cause){super(message,cause);} }
    public interface Delegation { void dispatch(Request parent, Tool tool); }
    public interface Publisher { void publish(String kind, String payload); }
}
