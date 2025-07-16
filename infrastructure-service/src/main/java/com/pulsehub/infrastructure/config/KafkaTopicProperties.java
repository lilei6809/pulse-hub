package com.pulsehub.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps the 'pulsehub.kafka' configuration items from application.yml into this class.
 * Provides a type-safe and structured way to access configuration.
 * Using @Configuration so that this bean is picked up by the Spring context.
 */
@Configuration
@ConfigurationProperties(prefix = "pulsehub.kafka")
@Data // Lombok annotation to auto-generate getters, setters, toString, equals, hashCode
public class KafkaTopicProperties {

    /**
     * Corresponds to the 'topic-defaults' section in YAML.
     * Initialized to prevent NullPointerException if the config section is missing.
     */
    private TopicDefaults topicDefaults = new TopicDefaults();

    /**
     * Corresponds to the 'partitions' section in YAML.
     * Spring Boot will automatically populate this map from the configuration.
     */
    private Map<String, Integer> partitions = new HashMap<>();

    /**
     * A static inner class to represent the nested 'topic-defaults' structure in YAML.
     * Being static means it doesn't hold an implicit reference to the outer class,
     * making it a self-contained data structure.
     */
    @Data
    public static class TopicDefaults {
        /**
         * Default number of replicas for topics.
         */
        private int replicas = 1;

        /**
         * Default minimum in-sync replicas for topics.
         */
        private int minInSyncReplicas = 1;
    }
}