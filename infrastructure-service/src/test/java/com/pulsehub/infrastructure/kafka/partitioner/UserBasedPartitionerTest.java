package com.pulsehub.infrastructure.kafka.partitioner;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
// 使用 spring-kafka-test 提供的内嵌 Kafka 环境
@EmbeddedKafka(partitions = 3, topics = { "test-topic" })
// 确保测试时使用我们的自定义分区器
@TestPropertySource(properties = {
    "spring.kafka.producer.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.producer.properties.partitioner.class=com.pulsehub.infrastructure.kafka.partitioner.UserBasedPartitioner",
    "eureka.client.enabled=false" // 同样需要禁用 Eureka
})
public class UserBasedPartitionerTest {

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
}
