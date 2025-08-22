package com.pulsehub.datasync.config;

import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer Configuration
 * 
 * 为立即同步和批量同步配置不同的消费者工厂:
 * - immediateKafkaListenerContainerFactory: 高优先级，低延迟处理
 * - batchKafkaListenerContainerFactory: 高吞吐量，批量处理
 */
@Slf4j
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 立即同步消费者工厂
     * 
     * 配置特点:
     * - 2个并发消费者
     * - 单条消息处理 (max-poll-records: 1)
     * - 快速响应，低延迟
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserProfileSyncEvent> 
            immediateKafkaListenerContainerFactory() {
        
        ConcurrentKafkaListenerContainerFactory<String, UserProfileSyncEvent> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(immediateConsumerFactory());
        factory.setConcurrency(2);  // 2个并发消费者
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);  // 同步提交确保可靠性
        
        log.info("Configured immediate sync consumer factory with concurrency: 2");
        return factory;
    }

    /**
     * 批量同步消费者工厂
     * 
     * 配置特点:
     * - 5个并发消费者
     * - 批量消息处理 (max-poll-records: 10)
     * - 高吞吐量处理
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserProfileSyncEvent> 
            batchKafkaListenerContainerFactory() {
        
        ConcurrentKafkaListenerContainerFactory<String, UserProfileSyncEvent> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(batchConsumerFactory());
        factory.setConcurrency(5);  // 5个并发消费者
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setSyncCommits(false);  // 异步提交提高吞吐量
        
        log.info("Configured batch sync consumer factory with concurrency: 5");
        return factory;
    }

    /**
     * 立即同步消费者工厂
     */
    private ConsumerFactory<String, UserProfileSyncEvent> immediateConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "immediate-sync-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);  // 单条处理
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);  // 手动提交
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 40000);
        
        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new JsonDeserializer<>(UserProfileSyncEvent.class)
        );
    }

    /**
     * 批量同步消费者工厂
     */
    private ConsumerFactory<String, UserProfileSyncEvent> batchConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "batch-sync-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);  // 批量处理
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);  // 手动提交
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 40000);
        
        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new JsonDeserializer<>(UserProfileSyncEvent.class)
        );
    }
}