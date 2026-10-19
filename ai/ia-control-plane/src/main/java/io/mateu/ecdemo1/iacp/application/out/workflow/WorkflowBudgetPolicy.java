package io.mateu.ecdemo1.iacp.application.out.workflow;

public interface WorkflowBudgetPolicy {
    void authorize(String agentId, String llmId, String technicalActor);
}
