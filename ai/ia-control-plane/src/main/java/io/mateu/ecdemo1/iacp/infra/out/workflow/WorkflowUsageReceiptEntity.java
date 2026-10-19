package io.mateu.ecdemo1.iacp.infra.out.workflow;

import jakarta.persistence.*;

@Entity
@Table(name = "workflow_agent_usage_receipt")
public class WorkflowUsageReceiptEntity {
    @Id @Column(length = 64) String id;
    @Column(length = 64, nullable = false) String digest;
    protected WorkflowUsageReceiptEntity() {}
}
