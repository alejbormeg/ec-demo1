package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.ecdemo1.iaagent.config.ChatClientRegistry;
import io.mateu.ecdemo1.iaagent.workflow.application.*;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.util.concurrent.*;

@Configuration @Profile("workflow")
public class WorkflowRuntimeConfiguration {
    @Bean WorkflowIdentity workflowIdentity(@Value("${workflow.jwt.issuer}")String issuer,@Value("${workflow.jwt.jwks}")String jwks,
            @Value("${workflow.jwt.audience}")String audience,@Value("${workflow.identity.client-id}")String client,
            @Value("${workflow.identity.client-secret}")String secret,@Value("${workflow.identity.token-uri}")String token,ObjectMapper mapper){return new WorkflowIdentity(issuer,jwks,audience,client,secret,token,mapper);}
    @Bean HttpWorkflowCatalogue workflowCatalogue(@Value("${ia.control-plane.url}")String url,WorkflowIdentity identity,ObjectMapper mapper){return new HttpWorkflowCatalogue(url,identity,mapper);}
    @Bean Store workflowStore(@Value("${workflow.database.url}")String url,@Value("${workflow.database.username}")String user,@Value("${workflow.database.password}")String password,ObjectMapper mapper){return new JdbcWorkflowStore(url,user,password,mapper);}
    @Bean Codec workflowCodec(ObjectMapper mapper){return new JacksonWorkflowCodec(mapper);}
    @Bean ToolFactory workflowTools(HttpWorkflowCatalogue catalogue,WorkflowIdentity identity,ObjectMapper mapper){return new StrictWorkflowTools(catalogue,identity,mapper);}
    @Bean Model workflowModel(ChatClientRegistry registry,HttpWorkflowCatalogue catalogue,ObjectMapper mapper,io.micrometer.observation.ObservationRegistry observations,io.mateu.ecdemo1.iaagent.observability.TraceHeaders traces){return new SpringAiWorkflowModel(registry,catalogue,mapper,observations,traces);}
    @Bean Delegation workflowDelegation(HttpWorkflowCatalogue catalogue,WorkflowIdentity identity,ObjectMapper mapper){return new HttpWorkflowA2a(catalogue,identity,mapper);}
    @Bean WorkflowAgentRunner workflowRunner(Store store,HttpWorkflowCatalogue catalogue,Model model,ToolFactory tools,Codec codec,Delegation delegation,
            @Value("${workflow.max-rounds:20}")int rounds,@Value("${workflow.max-tool-calls:50}")int calls,@Value("${workflow.max-tool-errors:5}")int toolErrors){return new WorkflowAgentRunner(store,catalogue,model,tools,codec,Clock.systemUTC(),rounds,calls,toolErrors,delegation);}
    @Bean(destroyMethod="close") KafkaWorkflowTransport workflowKafka(@Value("${workflow.kafka.brokers}")String brokers,@Value("${workflow.kafka.topic:agent-calls}")String topic,
            @Value("${workflow.kafka.group:ia-workflow-worker}")String group,@Value("${workflow.kafka.replies:upstream}")String replies,WorkflowAgentRunner runner,Store store,ObjectMapper mapper){return new KafkaWorkflowTransport(brokers,topic,group,replies,runner,store,mapper);}
    @Bean(destroyMethod="shutdownNow") ScheduledExecutorService workflowRecovery(WorkflowAgentRunner runner,Store store,KafkaWorkflowTransport publisher,HttpWorkflowCatalogue catalogue){
        var pool=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"workflow-recovery");t.setDaemon(true);return t;});
        pool.scheduleWithFixedDelay(()->{try{for(var root:store.pendingRoots())runner.runRoot(root);}catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass()).error("Workflow recovery failed",ex);}},1,1,TimeUnit.SECONDS);
        pool.scheduleWithFixedDelay(()->{try(var lock=store.lock("__workflow_outbox__")){if(lock==null)return;for(var event:store.pendingOutbox()){
            try{if("usage".equals(event.kind()))catalogue.usage(event.payload());else publisher.publish(event.kind(),event.payload());store.delivered(event.id());}
            catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Workflow outbox pending: {}",ex.toString());if(!"usage".equals(event.kind()))break;}
        }}catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Workflow outbox lock failed: {}",ex.toString());}},1,1,TimeUnit.SECONDS);
        return pool;
    }
}
