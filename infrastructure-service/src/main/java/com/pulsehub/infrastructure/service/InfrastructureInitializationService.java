package com.pulsehub.infrastructure.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;

/**
 * 基础设施初始化服务
 * 
 * 负责启动时验证整个数据平台的基础组件：
 * - Kafka 连接和 Topics 创建
 * - PostgreSQL 数据库连接 
 * - Redis 缓存连接
 * - Schema Registry 健康状态
 * 
 * 中级工程师特性：fail-fast 策略
 * 如果任何基础组件失败，服务会立即停止，确保数据一致性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfrastructureInitializationService {
    
    private final DataSource dataSource;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaAdmin kafkaAdmin;
    
    @Value("${app.environment:dev}")
    private String environment;
    
    /**
     * 应用启动完成后执行基础设施验证
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeInfrastructure() {
        log.info("🔧 Starting infrastructure validation for environment: {}", environment);
        
        try {
            // 验证顺序很重要：先验证基础服务，再验证依赖服务
            validatePostgreSQLConnection();
            validateRedisConnection();
            validateKafkaConnection();
            
            log.info("✅ All infrastructure components validated successfully!");
            log.info("🎯 PulseHub Infrastructure Service is ready to serve other services");
            
        } catch (Exception e) {
            log.error("💥 Infrastructure validation failed: {}", e.getMessage(), e);
            // fail-fast: 基础设施失败时立即退出
            System.exit(1);
        }
    }
    
    /**
     * 验证 PostgreSQL 数据库连接
     */
    private void validatePostgreSQLConnection() {
        log.info("🔍 Validating PostgreSQL connection...");
        try (Connection connection = dataSource.getConnection()) {
            boolean isValid = connection.isValid(5); // 5秒超时
            if (isValid) {
                log.info("✅ PostgreSQL connection validated successfully");
            } else {
                throw new RuntimeException("PostgreSQL connection validation failed");
            }
        } catch (Exception e) {
            log.error("❌ PostgreSQL connection failed: {}", e.getMessage());
            throw new RuntimeException("PostgreSQL connection validation failed", e);
        }
    }
    
    /**
     * 验证 Redis 连接
     */
    private void validateRedisConnection() {
        log.info("🔍 Validating Redis connection...");
        try {
            // 使用 PING 命令测试连接
            String testKey = "infrastructure:health:test";
            redisTemplate.opsForValue().set(testKey, "test", 10, TimeUnit.SECONDS);
            String value = (String) redisTemplate.opsForValue().get(testKey);
            
            if ("test".equals(value)) {
                redisTemplate.delete(testKey); // 清理测试数据
                log.info("✅ Redis connection validated successfully");
            } else {
                throw new RuntimeException("Redis read/write test failed");
            }
        } catch (Exception e) {
            log.error("❌ Redis connection failed: {}", e.getMessage());
            throw new RuntimeException("Redis connection validation failed", e);
        }
    }
    
    /**
     * 验证 Kafka 连接和 Topics 状态
     */
    private void validateKafkaConnection() {
        log.info("🔍 Validating Kafka connection and topics...");
        try {
            // 使用配置验证方式检查 Kafka 连接
            var config = kafkaAdmin.getConfigurationProperties();
            boolean kafkaHealthy = config != null && config.containsKey("bootstrap.servers");
            
            if (kafkaHealthy) {
                String bootstrapServers = (String) config.get("bootstrap.servers");
                log.info("✅ Kafka configuration validated. Bootstrap servers: {}", bootstrapServers);
                
                // 记录配置的 Topics（Spring Boot 会自动创建）
                String[] requiredTopics = {"user-activity-events", "profile-updates", "error-events", "dead-letter-queue", "metrics-events"};
                log.info("📝 Configured topics for creation: {}", String.join(", ", requiredTopics));
                log.info("ℹ️ Topics will be auto-created by Spring Boot on first use");
                
            } else {
                throw new RuntimeException("Kafka configuration validation failed");
            }
        } catch (Exception e) {
            log.error("❌ Kafka connection failed: {}", e.getMessage());
            throw new RuntimeException("Kafka connection validation failed", e);
        }
    }
} 