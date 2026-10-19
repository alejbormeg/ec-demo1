package io.mateu.ecdemo1.iacp.infra.config;

import io.mateu.ecdemo1.iacp.application.out.workflow.*;
import io.mateu.ecdemo1.iacp.application.usecases.workflow.WorkflowAgentExecutionService;
import io.mateu.ecdemo1.iacp.application.usecases.budget.CheckBudgetUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowExecutionConfiguration {
    @Bean WorkflowBudgetPolicy workflowBudgetPolicy(CheckBudgetUseCase check) {
        return (agent, llm, actor) -> {
            var verdict = check.check(agent, llm, actor, null);
            if (!verdict.allowed()) throw new IllegalArgumentException("Workflow agent budget exceeded: " + verdict.exceeded().name());
        };
    }
    @Bean WorkflowAgentExecutionService workflowAgentExecutionService(WorkflowCatalogueStore store, WorkflowBudgetPolicy budget) {
        return new WorkflowAgentExecutionService(store, budget);
    }
}
