package io.mateu.ecdemo1.iacp.application.out.workflow;

import io.mateu.ecdemo1.iacp.application.out.gitops.DesiredCatalogue;

/** Called only after a complete successful GitOps reconciliation. */
public interface WorkflowCataloguePublisher {
    void publish(DesiredCatalogue desired);
}
