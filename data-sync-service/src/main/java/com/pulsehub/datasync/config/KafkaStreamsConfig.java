package com.pulsehub.datasync.config;

import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Streams Configuration
 * 
 * 配置Kafka Streams用于事件路由:
 * - 应用ID: data-sync-router
 * - 处理保证: exactly_once_v2 (避免重复路由)
 * - 序列化: JSON格式处理UserProfileSyncEvent
 */
@Slf4j
@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    @Value("${spring.kafka.streams.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.streams.application-id}")
    private String applicationId;

    /**
     * Kafka Streams配置
     * 
     * 关键配置说明:
     * - processing.guarantee: exactly_once_v2 确保不重复路由
     * - num.stream.threads: 1 单线程处理路由逻辑
     * - cache.max.bytes.buffering: 0 禁用缓存保证实时路由
     */
    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig() {
        Map<String, Object> props = new HashMap<>();
        
        // 基础配置
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // 序列化配置
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, JsonSerde.class.getName());
        
        // 处理保证配置
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        
        // 性能优化配置
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);  // 单线程处理路由
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);  // 禁用缓存，实时路由
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);  // 1秒提交一次
        
        // 容错配置
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                org.apache.kafka.streams.errors.LogAndContinueExceptionHandler.class);
        
        // 日志配置
        props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, StreamsConfig.OPTIMIZE);
        
        log.info("Configured Kafka Streams with application-id: {}, bootstrap-servers: {}", 
                applicationId, bootstrapServers);
        
        return new KafkaStreamsConfiguration(props);
    }

    /**
     * Kafka Streams Builder Bean
     * 
     * 提供StreamsBuilder实例供EventRouter使用
     */
    @Bean
    public StreamsBuilder streamsBuilder() {
        StreamsBuilder builder = new StreamsBuilder();
        log.info("Created StreamsBuilder for event routing topology");
        return builder;
    }

    /**
     * UserProfileSyncEvent JSON Serde
     * 
     * 自定义序列化器用于处理UserProfileSyncEvent
     */
    @Bean
    public JsonSerde<UserProfileSyncEvent> userProfileSyncEventSerde() {
        JsonSerde<UserProfileSyncEvent> serde = new JsonSerde<>(UserProfileSyncEvent.class);
        
        // 配置信任的包名
        Map<String, Object> serdeProps = new HashMap<>();
        serdeProps.put("spring.json.trusted.packages", "com.pulsehub.*");
        serde.configure(serdeProps, false);
        
        log.info("Configured UserProfileSyncEvent JSON Serde");
        return serde;
    }
}