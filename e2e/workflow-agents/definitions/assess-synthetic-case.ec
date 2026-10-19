id: assess-synthetic-case
name: Assess a synthetic case with catalogue agents
version: 1
description: Synthetic native AGENT example exercising MCP, RAG and a registered A2A peer.
steps:
  - id: start
    type: START
    name: Start
  - id: assess
    type: AGENT
    name: Assess the supplied synthetic case
    agent:
      ref: workflow-case-assessor
      taskPrompt: |
        Read the supplied synthetic case through case-data, search the synthetic
        handbook, and ask workflow-evidence-agent to verify the supplied evidence.
        Return the assessment matching your published output schema.
      input:
        caseId: "${caseId}"
        case: "${caseData}"
      mcp: [workflow-case-data]
      rag: [workflow-handbook]
      peers: [workflow-evidence-agent]
      outputVariable: assessment
    timeout: PT5M
    retries: 0
    preconditionStepId: start
  - id: accepted
    type: END
    name: Accepted
    preconditions:
      - stepId: assess
        expression: "json:parse(assessment).decision == 'accepted'"
  - id: review
    type: END
    name: Business review requested
    preconditions:
      - stepId: assess
        expression: "json:parse(assessment).decision == 'review'"
