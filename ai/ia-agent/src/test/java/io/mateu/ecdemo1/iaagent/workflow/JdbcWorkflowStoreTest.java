package io.mateu.ecdemo1.iaagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import io.mateu.ecdemo1.iaagent.workflow.infra.JdbcWorkflowStore;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL and Flyway, isolated H2 PostgreSQL syntax mode; advisory locking needs PostgreSQL. */
class JdbcWorkflowStoreTest {
    ObjectMapper mapper=new ObjectMapper();
    String url(){return "jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";}
    WorkflowExecution state(){return new WorkflowExecution(new Request("root","root",null,"task","process","step","agent","Task",Map.of(),null,null,null,"result",99999999999L,"actor",List.of(),300000),"digest");}
    @Test void roundAndUsageAndStatusCommitTogetherAndSurviveNewStore(){var url=url();var store=new JdbcWorkflowStore(url,"sa","",mapper);var e=store.create(state());
        e.status=Status.RUNNING;e.rounds.add(new Round(new Message("assistant","{}",Map.of("signature","kept"),List.of(),null,null),"raw-provider-response",10,5,"model",Map.of()));store.save(e);
        var fresh=new JdbcWorkflowStore(url,"sa","",mapper);assertEquals(1,fresh.find("root").stateSequence);assertEquals("raw-provider-response",fresh.find("root").rounds.getFirst().providerResponse());
        assertEquals(2,fresh.pendingOutbox().size());assertTrue(fresh.pendingOutbox().stream().anyMatch(x->"usage".equals(x.kind())));assertTrue(fresh.pendingOutbox().stream().anyMatch(x->x.payload().contains("\"stateSequence\":1")));
    }
    @Test void failedOutboxInsertRollsBackInvocationCheckpoint()throws Exception{var url=url();var store=new JdbcWorkflowStore(url,"sa","",mapper);var e=store.create(state());
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute("ALTER TABLE workflow_agent_outbox ADD CONSTRAINT reject_payload CHECK(payload='never')");}
        e.status=Status.RUNNING;assertThrows(IllegalStateException.class,()->store.save(e));assertEquals(0,store.find("root").stateSequence);assertEquals(Status.QUEUED,store.find("root").status);assertTrue(store.pendingOutbox().isEmpty());
    }
    @Test void optimisticSequenceRejectsStaleConcurrentSave(){var store=new JdbcWorkflowStore(url(),"sa","",mapper);store.create(state());var first=store.find("root");var stale=store.find("root");first.status=Status.RUNNING;store.save(first);assertThrows(IllegalStateException.class,()->store.save(stale));assertEquals(Status.RUNNING,store.find("root").status);}
    @Test void invocationDigestConflictCannotOverwriteState(){var store=new JdbcWorkflowStore(url(),"sa","",mapper);store.create(state());var changed=state();changed.digest="changed";assertThrows(IllegalArgumentException.class,()->store.create(changed));assertEquals("digest",store.find("root").digest);}
    @Test void cancellationBeforeAcceptanceSurvivesReinstantiation(){var url=url();new JdbcWorkflowStore(url,"sa","",mapper).requestCancellation("future");assertTrue(new JdbcWorkflowStore(url,"sa","",mapper).cancellationRequested("future"));}
    @Test void outboxAcknowledgementAndIdempotence(){var store=new JdbcWorkflowStore(url(),"sa","",mapper);store.outbox("event","usage","{}");store.outbox("event","usage","{}");assertEquals(1,store.pendingOutbox().size());store.delivered("event");assertTrue(store.pendingOutbox().isEmpty());}
    @Test void terminalReplyIsNotStarvedByUndeliverableUsageBacklog(){var store=new JdbcWorkflowStore(url(),"sa","",mapper);for(int i=0;i<150;i++)store.outbox("usage:"+i,"usage","{}");store.outbox("terminal","reply","{\"status\":\"COMPLETED\"}");assertEquals("terminal",store.pendingOutbox().getFirst().id());assertEquals(100,store.pendingOutbox().size());}

}
