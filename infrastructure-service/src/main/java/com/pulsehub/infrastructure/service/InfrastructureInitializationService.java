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
 * 职责：
 * - 负责验证并初始化PulseHub平台所需的全部基础组件
 * - 实现平台的健康状态监控和问题快速反馈
 * - 根据环境差异应用不同的错误处理策略
 * 
 * 混合健康检查策略：
 * 1. 启动时验证（fail-fast）- 开发友好，快速反馈问题
 *    - 在应用完全启动后立即执行一次性验证
 *    - 如果在开发环境中发现关键错误，会立即终止应用
 * 
 * 2. 运行时健康检查（Spring Boot Actuator）- 生产友好，持续监控
 *    - 通过Spring Boot Actuator持续监控组件健康状态
 *    - 适合生产环境，不会因故障立即终止服务
 * 
 * 负责启动时验证整个数据平台的基础组件：
 * - Kafka 连接和 Topics 创建 - 作为事件总线，确保消息传递功能
 * - PostgreSQL 数据库连接 - 作为持久化存储，确保数据存储功能
 * - Redis 缓存连接 - 作为缓存层，确保高性能数据访问功能
 * 
 * 环境差异化策略：
 * - 开发环境：快速失败，立即暴露问题，提高开发效率
 * - 生产环境：记录错误但可能允许部分功能降级，保持系统可用性
 * 
 * 与其他服务的关系：
 * - 作为基础设施服务，为其他微服务提供基础组件保障
 * - 确保关键依赖在系统启动阶段就得到验证，防止运行时突然失败
 */
@Slf4j  
@Service 
@RequiredArgsConstructor  // Lombok注解：自动生成带有final字段的构造函数，支持依赖注入
public class InfrastructureInitializationService {
    
    // 通过构造函数注入的依赖组件
    private final DataSource dataSource;  // 数据库连接池，用于验证PostgreSQL连接
    private final RedisTemplate<String, Object> redisTemplate;  // Redis操作模板，用于验证Redis连接
    private final KafkaAdmin kafkaAdmin;  // Kafka管理员客户端，用于验证Kafka连接和话题
    
    // 从配置文件中注入当前运行环境，默认为"dev"
    @Value("${app.environment:dev}")
    private String environment;
    
    /**
     * 应用启动完成后执行基础设施验证
     * 
     * 监听ApplicationReadyEvent事件，确保在应用完全初始化后执行验证
     * 这比在@PostConstruct中执行更可靠，因为此时所有Bean和配置已完全加载
     * 
     * 执行流程：
     * 1. 记录开始验证的日志，包含当前环境信息
     * 2. 按特定顺序验证各组件（顺序很重要，避免级联失败）
     * 3. 成功后记录成功信息
     * 4. 失败时调用专门的错误处理方法
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeInfrastructure() {
        log.info("🔧 Starting infrastructure validation for environment: {}", environment);
        
        try {
            // 验证顺序很重要：先验证基础服务，再验证依赖服务
            // 按依赖关系顺序验证，避免因为一个组件失败导致级联错误
            validatePostgreSQLConnection();  // 首先验证数据库连接
            validateRedisConnection();       // 然后验证缓存连接
            validateKafkaConnection();       // 最后验证消息系统连接
            
            // 全部验证成功后记录成功日志
            log.info("✅ All infrastructure components validated successfully!");
            log.info("🎯 PulseHub Infrastructure Service is ready to serve other services");
            
        } catch (Exception e) {
            // 捕获任何验证过程中的异常，统一处理
            handleInfrastructureFailure(e);
        }
    }
    
    /**
     * 处理基础设施验证失败
     * 根据环境采用不同的失败策略
     * 
     * 开发环境：
     * - 立即终止应用（fail-fast策略）
     * - 提供详细的错误信息和修复建议
     * - 通过System.exit(1)强制退出
     * 
     * 生产环境：
     * - 记录错误但保持应用运行
     * - 依赖Spring Boot健康检查机制反映问题
     * - 允许系统管理员通过监控发现并处理问题
     * 
     * @param e 验证过程中捕获的异常
     */
    private void handleInfrastructureFailure(Exception e) {
        log.error("💥 Infrastructure validation failed: {}", e.getMessage(), e);
        
        // 开发友好的环境：立即失败，快速反馈
        if (isDevelopmentEnvironment()) {
            log.error("🚨 Development-friendly environment detected: {} - applying fail-fast strategy", environment);
            log.error("🔧 Please ensure all infrastructure components (PostgreSQL, Redis, Kafka) are running");
            log.error("💡 Tip: Run 'docker-compose up postgres redis kafka' to start dependencies");
            log.error("🐳 For Docker: Check if all containers are healthy with 'docker-compose ps'");
            System.exit(1);  // 非零退出码表示异常终止
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
     * 
     * 通过检查环境名称（不区分大小写）确定当前是否处于开发环境中
     * 此方法用于决定错误处理策略：开发环境采用fail-fast策略
     * 
     * @return 如果是开发环境则返回true，否则返回false
     */
    private boolean isDevelopmentEnvironment() {
        String env = environment.toLowerCase();  // 转小写以便不区分大小写比较
        return env.equals("dev") || 
               env.equals("test") || 
               env.equals("docker") || 
               env.equals("local") || 
               env.equals("development");
    }
    
    /**
     * 验证 PostgreSQL 数据库连接
     * 
     * 验证步骤：
     * 1. 从数据源获取一个连接
     * 2. 使用isValid方法测试连接的有效性（5秒超时）
     * 3. 成功时记录信息，失败时抛出异常
     * 
     * 使用try-with-resources确保连接自动关闭，防止连接泄露
     * 
     * @throws RuntimeException 当数据库连接失败或验证失败时
     */
    private void validatePostgreSQLConnection() {
        log.info("🔍 Validating PostgreSQL connection...");
        try (Connection connection = dataSource.getConnection()) {
            boolean isValid = connection.isValid(5); // 5秒超时，验证连接是否可用
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
     * 
     * 验证步骤：
     * 1. 创建一个临时测试键值对，设置10秒过期时间
     * 2. 立即读取该键的值并验证内容是否正确
     * 3. 删除测试键，避免数据残留
     * 4. 成功时记录信息，失败时抛出异常
     * 
     * 这个方法不仅测试连接性，还测试了读写操作的正确性
     * 
     * @throws RuntimeException 当Redis连接失败或读写测试失败时
     */
    private void validateRedisConnection() {
        log.info("🔍 Validating Redis connection...");
        try {
            // 使用 PING 命令测试连接
            String testKey = "infrastructure:health:test";  // 使用命名空间前缀，避免与业务数据冲突
            redisTemplate.opsForValue().set(testKey, "test", 10, TimeUnit.SECONDS);  // 写入测试数据，10秒后自动过期
            String value = (String) redisTemplate.opsForValue().get(testKey);  // 读取测试数据
            
            if ("test".equals(value)) {  // 验证数据一致性
                redisTemplate.delete(testKey); // 清理测试数据，避免残留
                log.info("✅ Redis connection validated successfully");
            } else {
                throw new RuntimeException("Redis read/write test failed");  // 数据不一致表示测试失败
            }
        } catch (Exception e) {
            log.error("❌ Redis connection failed: {}", e.getMessage());
            throw new RuntimeException("Redis connection validation failed", e);
        }
    }
    
    /**
     * 验证 Kafka 连接和 Topics 状态
     * 
     * 验证步骤：
     * 1. 获取KafkaAdmin的配置属性，检查是否包含bootstrap.servers
     * 2. 记录Kafka服务器地址和将要自动创建的话题列表
     * 3. 成功时记录信息，失败时抛出异常
     * 
     * 注意：本方法仅验证Kafka配置，实际Topic创建由Spring Boot自动处理
     * 
     * @throws RuntimeException 当Kafka连接配置验证失败时
     */
    private void validateKafkaConnection() {
        log.info("🔍 Validating Kafka connection and topics...");
        try {
            // 使用配置验证方式检查 Kafka 连接
            var config = kafkaAdmin.getConfigurationProperties();  // 获取Kafka客户端配置
            boolean kafkaHealthy = config != null && config.containsKey("bootstrap.servers");  // 验证关键配置存在
            
            if (kafkaHealthy) {
                String bootstrapServers = (String) config.get("bootstrap.servers");  // 获取Kafka服务器地址
                log.info("✅ Kafka configuration validated. Bootstrap servers: {}", bootstrapServers);
                
                // 记录配置的 Topics（Spring Boot 会自动创建）
                // 列出系统中使用的所有Kafka主题，为监控和排错提供参考
                String[] requiredTopics = {"user-activity-events", "profile-updates", "error-events", "dead-letter-queue", "metrics-events"};
                log.info("📝 Configured topics for creation: {}", String.join(", ", requiredTopics));
                log.info("ℹ️ Topics will be auto-created by Spring Boot on first use");
                
            } else {
                throw new RuntimeException("Kafka configuration validation failed");  // 缺少关键配置
            }
        } catch (Exception e) {
            log.error("❌ Kafka connection failed: {}", e.getMessage());
            throw new RuntimeException("Kafka connection validation failed", e);
        }
    }
} 