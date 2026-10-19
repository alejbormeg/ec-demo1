package io.mateu.ecdemo1.iaagent.workflow.infra;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KafkaWorkflowTransportTest {
    @Test void rewindsEveryUnprocessedPartitionAfterPartialBatchFailure(){
        var records=List.of(new ConsumerRecord<>("agents",0,10L,"p1","accepted"),new ConsumerRecord<>("agents",0,11L,"p1","failed"),new ConsumerRecord<>("agents",1,7L,"p2","unprocessed"),new ConsumerRecord<>("agents",1,8L,"p2","unprocessed"));
        assertEquals(Map.of(new TopicPartition("agents",0),11L,new TopicPartition("agents",1),7L),KafkaWorkflowTransport.unprocessedOffsets(records,1));
    }
}
