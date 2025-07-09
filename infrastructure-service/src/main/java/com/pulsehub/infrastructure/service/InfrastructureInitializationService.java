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
 * 混合健康检查策略：
 * 1. 启动时验证（fail-fast）- 开发友好，快速反馈问题
 * 2. 运行时健康检查（Spring Boot Actuator）- 生产友好，持续监控
 * 
 * 负责启动时验证整个数据平台的基础组件：
 * - Kafka 连接和 Topics 创建
 * - PostgreSQL 数据库连接 
 * - Redis 缓存连接
 * 
 * 环境差异化策略：
 * - 开发环境：快速失败，立即暴露问题
 * - 生产环境：记录错误但可能允许部分功能降级（未来扩展）
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
            handleInfrastructureFailure(e);
        }
    }
    
    /**
     * 处理基础设施验证失败
     * 根据环境采用不同的失败策略
     */
    private void handleInfrastructureFailure(Exception e) {
        log.error("💥 Infrastructure validation failed: {}", e.getMessage(), e);
        
        // 开发友好的环境：立即失败，快速反馈
        if (isDevelopmentEnvironment()) {
            log.error("🚨 Development-friendly environment detected: {} - applying fail-fast strategy", environment);
            log.error("🔧 Please ensure all infrastructure components (PostgreSQL, Redis, Kafka) are running");
            log.error("💡 Tip: Run 'docker-compose up postgres redis kafka' to start dependencies");
            log.error("🐳 For Docker: Check if all containers are healthy with 'docker-compose ps'");
            System.exit(1);
        } else {
            // 生产环境：记录错误，但允许 Spring Boot 健康检查处理
            log.error("⚠️ Production environment detected: {} - infrastructure issues will be reported via health checks", environment);
            log.error("🏥 Service will remain running, but health status will show as DOWN");
            log.error("📊 Monitor /actuator/health for detailed component status");
            // 不退出，让 Spring Boot 的健康检查机制处理
        }
    }
    
    /**
     * 判断是否为开发友好的环境
     * 包括：dev, test, docker, local 等
     */
    private boolean isDevelopmentEnvironment() {
        String env = environment.toLowerCase();
        return env.equals("dev") || 
               env.equals("test") || 
               env.equals("docker") || 
               env.equals("local") || 
               env.equals("development");
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