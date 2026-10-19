package io.mateu.ecdemo1.iaagent.workflow.infra;

import com.fasterxml.jackson.databind.*;
import io.mateu.ecdemo1.iaagent.workflow.application.*;
import io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.Request;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import java.time.*;
import java.util.*;

/** Commits input offsets only after durable acceptance; output is acknowledged before outbox delivery. */
public final class KafkaWorkflowTransport implements Publisher,AutoCloseable {
    private final KafkaConsumer<String,String> consumer;private final KafkaProducer<String,String> producer;
    private final WorkflowAgentRunner runner;private final Store store;private final ObjectMapper mapper;
    private final String replies;private volatile boolean running=true;private final Thread thread;
    public KafkaWorkflowTransport(String brokers,String topic,String group,String replies,WorkflowAgentRunner runner,Store store,ObjectMapper mapper) {
        this.runner=runner;this.store=store;this.mapper=mapper;this.replies=replies;
        var cp=new Properties();cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);cp.put(ConsumerConfig.GROUP_ID_CONFIG,group);
        cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        cp.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,25);consumer=new KafkaConsumer<>(cp);
        var pp=new Properties();pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);pp.put(ProducerConfig.ACKS_CONFIG,"all");pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);producer=new KafkaProducer<>(pp);
        thread=Thread.ofPlatform().name("workflow-agent-kafka").daemon().start(()->poll(topic));
    }
    private void poll(String topic) {
        consumer.subscribe(List.of(topic));
        try {while(running){var batch=consumer.poll(Duration.ofSeconds(1));var records=new ArrayList<ConsumerRecord<String,String>>();batch.forEach(records::add);
            for(int index=0;index<records.size();index++){var record=records.get(index);
                try{accept(record.value());consumer.commitSync(Map.of(new org.apache.kafka.common.TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)));}
                catch(Exception failure){unprocessedOffsets(records,index).forEach(consumer::seek);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Workflow task not accepted; all unprocessed partitions rewound",failure);break;}
            }
        }}catch(org.apache.kafka.common.errors.WakeupException stop){if(running)throw stop;}finally{consumer.close();}
    }
    static Map<org.apache.kafka.common.TopicPartition,Long> unprocessedOffsets(List<ConsumerRecord<String,String>> records,int firstUnprocessed) {
        var offsets=new LinkedHashMap<org.apache.kafka.common.TopicPartition,Long>();
        for(int i=firstUnprocessed;i<records.size();i++){var record=records.get(i);offsets.merge(new org.apache.kafka.common.TopicPartition(record.topic(),record.partition()),record.offset(),Math::min);}
        return offsets;
    }
    public void accept(String json) throws Exception {
        var node=mapper.readTree(json);var type=node.path("type").asText();
        if("task-cancellation-requested".equals(type)) {var id=node.path("invocationId").asText();if(id.isBlank())throw new IllegalArgumentException("Agent cancellation needs invocationId");store.requestCancellation(id);if(store.find(id)!=null)runner.cancel(id);return;}
        if(!"task-execution-requested".equals(type)||!"run-agent@1".equals(node.path("taskId").asText()))throw new IllegalArgumentException("Unsupported agent task contract");
        var id=node.path("invocationId").asText();if(id.isBlank())throw new IllegalArgumentException("Agent task needs invocationId");
        var variables=node.path("variables");if(!variables.isArray()||variables.size()!=1||!"__agent".equals(variables.get(0).path("name").asText()))throw new IllegalArgumentException("Agent task must contain only __agent");
        var envelope=mapper.readTree(variables.get(0).path("value").asText());if(envelope.path("schemaVersion").asInt()!=1)throw new IllegalArgumentException("Unsupported __agent schema version");
        var deadline=envelope.path("deadlineAt");long millis=deadline.isNumber()?deadline.asLong():Instant.parse(deadline.asText()).toEpochMilli();
        var request=new Request(id,id,null,node.path("taskExecutionId").asText(),node.path("processId").asText(),node.path("stepId").asText(),
                envelope.path("agentRef").asText(),envelope.path("taskPrompt").asText(""),mapper.convertValue(envelope.path("input"),Map.class),
                selection(envelope,"mcp"),selection(envelope,"rag"),selection(envelope,"peers"),envelope.path("outputVariable").asText(),millis,
                envelope.hasNonNull("initiatingActor")?envelope.path("initiatingActor").asText():null,List.of(),envelope.path("timeoutMillis").asLong(300000));
        runner.submit(request);
    }
    private List<String> selection(JsonNode node,String field){if(!node.hasNonNull(field))return null;return mapper.convertValue(node.get(field),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}
    @Override public void publish(String kind,String payload) {
        if(!"reply".equals(kind))throw new IllegalArgumentException("Kafka publisher only sends replies");
        try {
            var state=mapper.readTree(payload);var body=mapper.createObjectNode();body.put("type","task-status-changed");body.put("taskExecutionId",state.path("taskExecutionId").asText());
            body.put("processId",state.path("processId").asText());body.put("invocationId",state.path("invocationId").asText());
            var status=state.path("status").asText();body.put("stateSequence",state.path("stateSequence").asLong());body.put("reviewResolved",state.path("reviewResolved").asBoolean(false));body.put("status","FAILED".equals(status)?"ERROR":status);body.put("nonRetryable","FAILED".equals(status));
            var vars=body.putArray("variables");state.path("variables").fields().forEachRemaining(v->{var item=vars.addObject();item.put("name",v.getKey());item.put("value",v.getValue().asText());});
            if(state.hasNonNull("error")){var error=vars.addObject();error.put("name","__agentError");error.put("value",state.path("error").asText());}
            producer.send(new ProducerRecord<>(replies,state.path("processId").asText(),mapper.writeValueAsString(body))).get(30,java.util.concurrent.TimeUnit.SECONDS);
        }catch(Exception ex){throw new IllegalStateException("Workflow reply not acknowledged",ex);}
    }
    @Override public void close(){running=false;consumer.wakeup();try{thread.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}producer.close(Duration.ofSeconds(5));}
}
