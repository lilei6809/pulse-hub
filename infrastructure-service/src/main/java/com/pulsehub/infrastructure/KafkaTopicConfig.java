package com.pulsehub.infrastructure;

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
 * 基础设施服务专用：负责整个平台的Topic创建和管理
 * 
 * 实现多Topic架构：
 * 1. user-activity-events: 原始用户行为数据 
 * 2. profile-updates: 用户画像同步数据
 * 3. error-events: 系统错误事件
 * 4. dead-letter-queue: 处理失败的消息
 * 5. metrics-events: 监控指标数据
 * 
 * 中级工程师进阶特性：
 * - 监控指标配置
 * - 压缩策略优化
 * - 性能调优参数
 * - 运维友好的命名规范
 */
@Configuration
public class KafkaTopicConfig {
    
    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String bootstrapServers;
    
    // 中级工程师特性：环境感知配置
    @Value("${app.environment:dev}")
    private String environment;
    
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // 中级工程师特性：连接超时配置
        configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        configs.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 300000);
        
        return new KafkaAdmin(configs);
    }
    
    /**
     * 用户活动事件Topic
     * - 高吞吐量：10个分区支持并发处理
     * - 较长保留：7天用于故障恢复和数据重建
     * - 高可靠性：关键业务数据
     */
    @Bean
    public NewTopic userActivityEventsTopic() {
        return TopicBuilder.name("user-activity-events")
                .partitions(10)
                .replicas(1) // 单机环境，生产环境建议3
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000") // 7天
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                // 中级工程师特性：性能优化配置
                .config(TopicConfig.SEGMENT_MS_CONFIG, "86400000") // 1天一个segment
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4") // 压缩节省存储
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1") // 最小同步副本
                .build();
    }
    
    /**
     * 用户画像更新Topic  
     * - 中等吞吐量：5个分区用于MongoDB同步
     * - 短期保留：2天，主要用于异步数据同步
     * - Redis已有最新状态，此Topic主要用于持久化
     */
    @Bean
    public NewTopic profileUpdatesTopic() {
        return TopicBuilder.name("profile-updates")
                .partitions(5)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "172800000") // 2天
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                // 中级工程师特性：针对用户画像的优化
                .config(TopicConfig.SEGMENT_MS_CONFIG, "43200000") // 12小时一个segment
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy") // 快速压缩
                .build();
    }
    
    /**
     * 错误事件Topic
     * - 错误审计：30天保留用于错误分析和审计
     * - 较少分区：3个分区，错误事件相对较少
     * - 高重要性：关键错误不能丢失
     */
    @Bean
    public NewTopic errorEventsTopic() {
        return TopicBuilder.name("error-events")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000") // 30天
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                // 中级工程师特性：错误事件专用配置
                .config(TopicConfig.SEGMENT_MS_CONFIG, "604800000") // 7天一个segment
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "gzip") // 最高压缩比
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576") // 1MB最大消息
                .build();
    }
    
    /**
     * 死信队列Topic
     * - 失败消息处理：收集所有处理失败的消息
     * - 人工干预：保留14天用于人工分析和处理
     * - 单分区：便于按时间顺序处理失败消息
     * 
     * 中级工程师洞察：
     * 为什么用1个分区？
     * - 保证消息的时间顺序，便于故障分析
     * - 人工处理时需要按时间线理解错误演进
     * - 避免跨分区的消息乱序问题
     */
    @Bean
    public NewTopic deadLetterQueueTopic() {
        return TopicBuilder.name("dead-letter-queue")
                .partitions(1) // 关键：保证顺序处理
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "1209600000") // 14天
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                // 中级工程师特性：DLQ专用配置
                .config(TopicConfig.SEGMENT_MS_CONFIG, "86400000") // 1天一个segment
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "gzip") // 最高压缩比
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "2097152") // 2MB，错误消息可能较大
                .build();
    }
    
    // 中级工程师特性：监控和健康检查主题
    @Bean
    public NewTopic metricsEventsTopic() {
        return TopicBuilder.name("metrics-events")
                .partitions(2)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "259200000") // 3天
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .build();
    }
} 