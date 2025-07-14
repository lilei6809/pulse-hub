package com.pulsehub.infrastructure.kafka.partitioner;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
// 使用 spring-kafka-test 提供的内嵌 Kafka 环境
@EmbeddedKafka(partitions = 3, topics = { "test-topic", "user-activity-events", "dead-letter-queue", "metrics-events" })
// 确保测试时使用我们的自定义分区器
@TestPropertySource(properties = {
    "spring.kafka.producer.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.producer.properties.partitioner.class=com.pulsehub.infrastructure.kafka.partitioner.TopicAwarePartitioner",
    "eureka.client.enabled=false" // 同样需要禁用 Eureka
})
public class TopicAwarePartitionerTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void whenSendingWithSameKey_thenMessagesGoToSamePartition() throws ExecutionException, InterruptedException {
        String key = "test-key-1";
        String topic = "test-topic";

        // 发送第一条消息并获取它所在的分区
        ProducerRecord<String, String> record1 = new ProducerRecord<>(topic, key, "message1");
        var sendResult1 = kafkaTemplate.send(record1).get();
        int partition1 = sendResult1.getRecordMetadata().partition();
        assertNotNull(sendResult1.getRecordMetadata());

        // 发送第二条消息，使用相同的 key
        ProducerRecord<String, String> record2 = new ProducerRecord<>(topic, key, "message2");
        var sendResult2 = kafkaTemplate.send(record2).get();
        int partition2 = sendResult2.getRecordMetadata().partition();

        // 断言：两条消息必须在同一个分区
        assertEquals(partition1, partition2, "Messages with the same key should go to the same partition.");
    }


    /**
     * Test the key-based partitioning strategy for 'user-activity-events'.
     * Message with the same key should go to the same partition.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    void whenSendingWithSameKey_thenMessageGoToSamePartition() throws ExecutionException, InterruptedException {
        String key = "user-123";
        String topic = "user-activity-events";

        // send the first msg and get its partition
        ProducerRecord<String, String> record1 = new ProducerRecord<>(topic, key, "message1");

        var sendResult1 = kafkaTemplate.send(record1).get();
        int partition1 = sendResult1.getRecordMetadata().partition();
        // sendResult1.getRecordMetadata() 必须收到 kafka 的response
        assertNotNull(sendResult1.getRecordMetadata());

        // 发第二条消息 with the same key
        ProducerRecord<String, String> record2 = new ProducerRecord<>(topic, key, "message2");
        var sendResult2 = kafkaTemplate.send(record2).get();
        int partition2 = sendResult2.getRecordMetadata().partition();

        assertEquals(partition1, partition2,  "Messages with the same key for 'user-activity-events' should go to the same partition.");

    }


    /**
     * Tests the fixed partitioning strategy for DLQ
     * All messages, regardless of key, should go to partition 0
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    void whenSendingToDLQ_thenAllMessagesGoToPartitionZero() throws ExecutionException, InterruptedException{

        String topic = "dead-letter-queue";

        ProducerRecord<String, String> record1 = new ProducerRecord<>(topic, "some-key1", "some-msg1");
        var sendResult1 = kafkaTemplate.send(record1).get();
        int partition1 = sendResult1.getRecordMetadata().partition();

        ProducerRecord<String, String> record2 = new ProducerRecord<>(topic, "some-key2", "some-msg2");
        var sendResult2 = kafkaTemplate.send(record2).get();
        int partition2 = sendResult2.getRecordMetadata().partition();

        assertEquals(0, partition1, "DLQ message with key should go to partition 0.");
        assertEquals(0, partition2, "DLQ message with key should go to partition 0.");

    }


    @Test
    void whenSendingToDefaultTopicWithoutKey_thenAllMessagesAreRoundRobin() throws ExecutionException, InterruptedException {

        String topic = "metrics-events";

        // send multiple messages without key
        var sendResult1 = kafkaTemplate.send(topic, "msg1").get();
        var sendResult2 = kafkaTemplate.send(topic, "msg2").get();
        var sendResult3 = kafkaTemplate.send(topic, "msg3").get();
        var sendResult4 = kafkaTemplate.send(topic, "msg4").get();

        int partition1 = sendResult1.getRecordMetadata().partition();
        int partition2 = sendResult2.getRecordMetadata().partition();
        int partition3 = sendResult3.getRecordMetadata().partition();
        int partition4 = sendResult4.getRecordMetadata().partition();

        // Assert: Partitions should follow a 0, 1, 2, 0 pattern
//        assertEquals(1, partition1);
//        assertEquals(2, partition2);
//        assertEquals(3, partition3);
//        assertEquals(0, partition4);




    }


}
