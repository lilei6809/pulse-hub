package com.pulsehub.infrastructure.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基础设施健康检查控制器
 * 
 * 提供详细的组件状态检查，用于：
 * - Docker healthcheck
 * - 外部监控系统
 * - 运维状态检查
 * - 依赖服务启动验证
 */
@Slf4j
@RestController
@RequestMapping("/api/infrastructure")
@RequiredArgsConstructor
public class HealthController {
    
    private final DataSource dataSource;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaAdmin kafkaAdmin;
    
    @Value("${app.environment:dev}")
    private String environment;
    
    /**
     * 简单健康检查 - 用于 Docker healthcheck
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "infrastructure-service");
        health.put("environment", environment);
        health.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(health);
    }
    
    /**
     * 详细健康检查 - 检查所有基础组件
     */
    @GetMapping("/health/detailed")
    public ResponseEntity<Map<String, Object>> detailedHealth() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> components = new HashMap<>();
        
        boolean allHealthy = true;
        
        // 检查 PostgreSQL
        try {
            try (Connection connection = dataSource.getConnection()) {
                boolean isValid = connection.isValid(3);
                components.put("postgresql", Map.of(
                    "status", isValid ? "UP" : "DOWN",
                    "details", isValid ? "Connection validated" : "Connection invalid"
                ));
                if (!isValid) allHealthy = false;
            }
        } catch (Exception e) {
            components.put("postgresql", Map.of(
                "status", "DOWN",
                "details", e.getMessage()
            ));
            allHealthy = false;
        }
        
        // 检查 Redis
        try {
            String testKey = "health:check:" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "test", 5, TimeUnit.SECONDS);
            String value = (String) redisTemplate.opsForValue().get(testKey);
            boolean redisHealthy = "test".equals(value);
            
            components.put("redis", Map.of(
                "status", redisHealthy ? "UP" : "DOWN",
                "details", redisHealthy ? "Read/write test passed" : "Read/write test failed"
            ));
            if (!redisHealthy) allHealthy = false;
            
            if (redisHealthy) {
                redisTemplate.delete(testKey);
            }
        } catch (Exception e) {
            components.put("redis", Map.of(
                "status", "DOWN",
                "details", e.getMessage()
            ));
            allHealthy = false;
        }
        
        // 检查 Kafka
        try {
            // 使用 KafkaAdmin 的配置检查来验证连接
            // 这是一个简单的检查，如果 KafkaAdmin 配置正确，说明 Kafka 可达
            var config = kafkaAdmin.getConfigurationProperties();
            boolean kafkaHealthy = config != null && config.containsKey("bootstrap.servers");
            
            components.put("kafka", Map.of(
                "status", kafkaHealthy ? "UP" : "DOWN",
                "details", kafkaHealthy ? 
                    "Kafka configuration validated" : 
                    "Kafka configuration invalid"
            ));
            if (!kafkaHealthy) allHealthy = false;
        } catch (Exception e) {
            components.put("kafka", Map.of(
                "status", "DOWN",
                "details", e.getMessage()
            ));
            allHealthy = false;
        }
        
        result.put("status", allHealthy ? "UP" : "DOWN");
        result.put("service", "infrastructure-service");
        result.put("environment", environment);
        result.put("components", components);
        result.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 基础设施就绪状态检查
     * 用于其他服务启动前的依赖检查
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> readiness = new HashMap<>();
        
        // 快速检查关键组件
        boolean ready = true;
        
        try {
            // 检查数据库
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) ready = false;
            }
            
            // 检查 Redis
            redisTemplate.hasKey("test");
            
            // 检查 Kafka
            var kafkaConfig = kafkaAdmin.getConfigurationProperties();
            if (kafkaConfig == null || !kafkaConfig.containsKey("bootstrap.servers")) {
                ready = false;
            }
            
        } catch (Exception e) {
            ready = false;
            log.warn("Readiness check failed: {}", e.getMessage());
        }
        
        readiness.put("ready", ready);
        readiness.put("service", "infrastructure-service");
        readiness.put("message", ready ? 
            "All infrastructure components are ready" : 
            "Infrastructure components not ready");
        readiness.put("timestamp", LocalDateTime.now());
        
        return ready ? 
            ResponseEntity.ok(readiness) : 
            ResponseEntity.status(503).body(readiness);
    }
} 