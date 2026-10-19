package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution;
import java.sql.*;
import java.util.*;

/** PostgreSQL is the authority. A session advisory lock serializes an entire root's agent tree. */
public final class JdbcWorkflowStore implements Store {
    private final String url,user,password; private final ObjectMapper mapper;
    public JdbcWorkflowStore(String url,String user,String password,ObjectMapper mapper) {
        this.url=url;this.user=user;this.password=password;this.mapper=mapper;
        org.flywaydb.core.Flyway.configure().dataSource(url,user,password).locations("classpath:db/workflow").load().migrate();
    }
    private Connection connection() throws SQLException {return DriverManager.getConnection(url,user,password);}
    @Override public WorkflowExecution find(String id) {
        try(var c=connection();var s=c.prepareStatement("SELECT state_json FROM workflow_agent_execution WHERE invocation_id=?")) {
            s.setString(1,id);try(var rs=s.executeQuery()){return rs.next()?mapper.readValue(rs.getString(1),WorkflowExecution.class):null;}
        }catch(Exception ex){throw error(ex);}
    }
    @Override public WorkflowExecution create(WorkflowExecution e) {
        try(var c=connection();var s=c.prepareStatement("INSERT INTO workflow_agent_execution(invocation_id,root_id,request_digest,status,state_json) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING")) {
            s.setString(1,e.request.invocationId());s.setString(2,e.request.rootInvocationId());s.setString(3,e.digest);s.setString(4,e.status.name());s.setString(5,mapper.writeValueAsString(e));s.executeUpdate();
        }catch(Exception ex){throw error(ex);}
        var saved=find(e.request.invocationId());
        if(!Objects.equals(saved.digest,e.digest))throw new IllegalArgumentException("Invocation id reused with different request digest");
        return saved;
    }
    @Override public void save(WorkflowExecution e) {
        try(var c=connection()) {
            c.setAutoCommit(false);
            long expected=e.stateSequence;e.stateSequence++;
            try(var s=c.prepareStatement("UPDATE workflow_agent_execution SET state_json=?,status=?,state_sequence=?,updated_at=CURRENT_TIMESTAMP WHERE invocation_id=? AND request_digest=? AND state_sequence=?")) {
                s.setString(1,mapper.writeValueAsString(e));s.setString(2,e.status.name());s.setLong(3,e.stateSequence);s.setString(4,e.request.invocationId());s.setString(5,e.digest);s.setLong(6,expected);
                if(s.executeUpdate()!=1)throw new IllegalStateException("Stale or missing invocation");
            }
            // Round result and usage share one transaction, including after process death.
            for(int i=0;i<e.rounds.size();i++) {
                var round=e.rounds.get(i);var payload=Map.of("rootInvocationId",e.request.rootInvocationId(),"invocationId",e.request.invocationId(),
                        "agentId",e.request.agentRef(),"round",i+1,"inputTokens",round.inputTokens(),"outputTokens",round.outputTokens());
                insertOutbox(c,e.request.invocationId()+":usage:"+(i+1),"usage",mapper.writeValueAsString(payload));
            }
            if(e.request.parentInvocationId()==null && e.status!=WorkflowExecution.Status.QUEUED) {
                var payload=new LinkedHashMap<String,Object>();payload.put("taskExecutionId",e.request.taskExecutionId());payload.put("processId",e.request.processId());
                payload.put("stepId",e.request.stepId());payload.put("invocationId",e.request.invocationId());payload.put("status",e.status.name());
                payload.put("stateSequence",e.stateSequence);payload.put("reviewResolved",e.reviewResolved);
                payload.put("variables",e.status==WorkflowExecution.Status.COMPLETED?Map.of(e.request.outputVariable(),e.result):Map.of());payload.put("error",e.error);
                insertOutbox(c,e.request.invocationId()+":reply:"+e.stateSequence,"reply",mapper.writeValueAsString(payload));
            }
            c.commit();
        }catch(Exception ex){throw error(ex);}
    }
    @Override public List<String> pendingRoots() {
        try(var c=connection();var s=c.prepareStatement("SELECT invocation_id FROM workflow_agent_execution WHERE invocation_id=root_id AND (status IN ('QUEUED','RUNNING') OR (status='REVIEW_REQUIRED' AND EXISTS(SELECT 1 FROM workflow_agent_cancellation c WHERE c.root_id=workflow_agent_execution.root_id))) ORDER BY updated_at LIMIT 50");var r=s.executeQuery()) {
            var ids=new ArrayList<String>();while(r.next())ids.add(r.getString(1));return ids;
        }catch(Exception ex){throw error(ex);}
    }
    @Override public List<WorkflowExecution> roots() {
        try(var c=connection();var s=c.prepareStatement("SELECT state_json FROM workflow_agent_execution WHERE invocation_id=root_id ORDER BY updated_at DESC LIMIT 200");var r=s.executeQuery()) {
            var rows=new ArrayList<WorkflowExecution>();while(r.next())rows.add(mapper.readValue(r.getString(1),WorkflowExecution.class));return rows;
        }catch(Exception ex){throw error(ex);}
    }
    @Override public List<WorkflowExecution> children(String rootId) {
        try(var c=connection();var s=c.prepareStatement("SELECT state_json FROM workflow_agent_execution WHERE root_id=? ORDER BY updated_at")) {
            s.setString(1,rootId);try(var r=s.executeQuery()){var rows=new ArrayList<WorkflowExecution>();while(r.next())rows.add(mapper.readValue(r.getString(1),WorkflowExecution.class));return rows;}
        }catch(Exception ex){throw error(ex);}
    }
    @Override public AutoCloseable lock(String rootId) {
        try {var c=connection();try(var s=c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))")) {
            s.setString(1,rootId);try(var r=s.executeQuery()){r.next();if(!r.getBoolean(1)){c.close();return null;}}
        }return c::close;}catch(Exception ex){throw error(ex);}
    }
    @Override public void outbox(String id,String kind,String payload) {try(var c=connection()){insertOutbox(c,id,kind,payload);}catch(Exception ex){throw error(ex);}}
    private void insertOutbox(Connection c,String id,String kind,String payload)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO workflow_agent_outbox(event_id,event_kind,payload) VALUES(?,?,?) ON CONFLICT DO NOTHING")){
            s.setString(1,id);s.setString(2,kind);s.setString(3,payload);s.executeUpdate();}
    }
    @Override public List<Outbox> pendingOutbox() {
        try(var c=connection();var s=c.prepareStatement("SELECT event_id,event_kind,payload FROM workflow_agent_outbox WHERE delivered=FALSE ORDER BY CASE WHEN event_kind='reply' THEN 0 ELSE 1 END,created_at,event_id LIMIT 100");var r=s.executeQuery()){
            var list=new ArrayList<Outbox>();while(r.next())list.add(new Outbox(r.getString(1),r.getString(2),r.getString(3)));return list;
        }catch(Exception ex){throw error(ex);}
    }
    @Override public void delivered(String id) {try(var c=connection();var s=c.prepareStatement("UPDATE workflow_agent_outbox SET delivered=TRUE WHERE event_id=?")){s.setString(1,id);s.executeUpdate();}catch(Exception ex){throw error(ex);}}
    @Override public void requestCancellation(String rootId) {try(var c=connection();var s=c.prepareStatement("INSERT INTO workflow_agent_cancellation(root_id) VALUES(?) ON CONFLICT DO NOTHING")){s.setString(1,rootId);s.executeUpdate();}catch(Exception ex){throw error(ex);}}
    @Override public boolean cancellationRequested(String rootId) {try(var c=connection();var s=c.prepareStatement("SELECT TRUE FROM workflow_agent_cancellation WHERE root_id=?")){s.setString(1,rootId);try(var r=s.executeQuery()){return r.next()&&r.getBoolean(1);}}catch(Exception ex){throw error(ex);}}
    private RuntimeException error(Exception ex){return new IllegalStateException("Workflow persistence failed",ex);}
}
