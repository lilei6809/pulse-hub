package com.pulsehub.infrastructure.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

/**
 * PulseHub Kafka Topics Configuration
 *
 * This class is now refactored to use @ConfigurationProperties for managing topic settings,
 * making the configuration more structured, type-safe, and easier to manage.
 */
@Configuration
@RequiredArgsConstructor // Lombok: Creates a constructor for all final fields.
public class KafkaTopicConfig {

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String bootstrapServers;

    // All @Value injections for topic configs are now replaced by this single properties object.
    private final KafkaTopicProperties kafkaTopicProperties;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        configs.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 300000);
        return new KafkaAdmin(configs);
    }

    /**
     * User Activity Events Topic
     * Configurations are now fetched from the injected KafkaTopicProperties object.
     */
    @Bean
    public NewTopic userActivityEventsTopic() {
        KafkaTopicProperties.TopicDefaults defaults = kafkaTopicProperties.getTopicDefaults();
        return TopicBuilder.name("user-activity-events")
                .partitions(kafkaTopicProperties.getPartitions().getOrDefault("user-activity-events", 10))
                .replicas(defaults.getReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(defaults.getMinInSyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000") // 7 days
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "86400000") // 1 day
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .build();
    }

    /**
     * User Profile Updates Topic
     */
    @Bean
    public NewTopic profileUpdatesTopic() {
        KafkaTopicProperties.TopicDefaults defaults = kafkaTopicProperties.getTopicDefaults();
        return TopicBuilder.name("profile-updates")
                .partitions(kafkaTopicProperties.getPartitions().getOrDefault("profile-updates", 5))
                .replicas(defaults.getReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(defaults.getMinInSyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, "172800000") // 2 days
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "43200000") // 12 hours
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy")
                .build();
    }

    /**
     * Error Events Topic
     */
    @Bean
    public NewTopic errorEventsTopic() {
        KafkaTopicProperties.TopicDefaults defaults = kafkaTopicProperties.getTopicDefaults();
        return TopicBuilder.name("error-events")
                .partitions(kafkaTopicProperties.getPartitions().getOrDefault("error-events", 3))
                .replicas(defaults.getReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(defaults.getMinInSyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000") // 30 days
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "604800000") // 7 days
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "gzip")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576") // 1MB
                .build();
    }

    /**
     * Dead Letter Queue Topic
     * Note: This topic might have specific reliability requirements.
     * Here, we apply the default replica settings, but it could be configured separately if needed.
     */
    @Bean
    public NewTopic deadLetterQueueTopic() {
        KafkaTopicProperties.TopicDefaults defaults = kafkaTopicProperties.getTopicDefaults();
        return TopicBuilder.name("dead-letter-queue")
                .partitions(kafkaTopicProperties.getPartitions().getOrDefault("dead-letter-queue", 1))
                .replicas(defaults.getReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(defaults.getMinInSyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, "1209600000") // 14 days
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "86400000") // 1 day
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "gzip")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "2097152") // 2MB
                .build();
    }

    /**
     * Profile Sync Events Topic (DocSyncProducer)
     * Handles both immediate sync (critical business data) and batch sync (regular data)
     */
    @Bean
    public NewTopic profileSyncEventsTopic() {
        KafkaTopicProperties.TopicDefaults defaults = kafkaTopicProperties.getTopicDefaults();
        return TopicBuilder.name("profile-sync-events")
                .partitions(kafkaTopicProperties.getPartitions().getOrDefault("profile-sync-events", 6))
                .replicas(defaults.getReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(defaults.getMinInSyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, "86400000") // 1 day - critical sync data
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "21600000") // 6 hours
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy") // Fast compression for real-time sync
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576") // 1MB for profile data
                .build();
    }

    /**
     * Metrics Events Topic
     */
    @Bean
    public NewTopic metricsEventsTopic() {
        KafkaTopicProperties.TopicDefaults defaults = kafkaTopicProperties.getTopicDefaults();
        return TopicBuilder.name("metrics-events")
                .partitions(kafkaTopicProperties.getPartitions().getOrDefault("metrics-events", 2))
                .replicas(defaults.getReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(defaults.getMinInSyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, "259200000") // 3 days
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .build();
    }
} 