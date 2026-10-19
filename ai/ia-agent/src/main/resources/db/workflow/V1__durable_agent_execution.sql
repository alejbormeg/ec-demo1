CREATE TABLE workflow_agent_execution (
 invocation_id VARCHAR(200) PRIMARY KEY,
 root_id VARCHAR(200) NOT NULL,
 request_digest VARCHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL,
 state_sequence BIGINT NOT NULL DEFAULT 0,
 cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
 state_json TEXT NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX workflow_agent_root_idx ON workflow_agent_execution(root_id);
CREATE INDEX workflow_agent_pending_idx ON workflow_agent_execution(status);
CREATE TABLE workflow_agent_outbox (
 event_id VARCHAR(400) PRIMARY KEY,
 event_kind VARCHAR(24) NOT NULL,
 payload TEXT NOT NULL,
 delivered BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX workflow_agent_outbox_pending_idx ON workflow_agent_outbox(delivered,created_at);

CREATE TABLE workflow_agent_cancellation (root_id VARCHAR(200) PRIMARY KEY, requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP);
