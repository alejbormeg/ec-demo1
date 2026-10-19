package io.mateu.ecdemo1.iacp.infra.out.workflow;

import jakarta.persistence.*;

/** Ciphertext only. No Lombok toString can accidentally expose a document. */
@Entity
@Table(name = "workflow_agent_document")
public class WorkflowDocumentEntity {
    @Id @Column(length = 80) String id;
    @Column(columnDefinition = "text", nullable = false) String ciphertext;
    protected WorkflowDocumentEntity() {}
}
