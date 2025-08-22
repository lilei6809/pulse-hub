package com.pulsehub.datasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;



/**
 * 数据同步服务主应用类
 * 
 * 核心功能：
 * 1. Kafka Streams事件路由器 - 按优先级将事件路由到不同Topic
 * 2. 混合同步策略 - 立即同步(关键业务数据) + 批量同步(普通数据)
 * 3. 双消费者组架构 - immediate-sync-group + batch-sync-group
 * 4. MongoDB增量更新 - 支持选择性字段更新和乐观锁
 * 5. 重试降级机制 - 立即同步失败后降级到批量队列
 * 6. 版本控制和数据一致性 - exactly_once_v2处理保证
 * 
 * 数据流架构：
 * profile-sync-events → EventRouter → immediate-sync-events (高优先级)
 *                                  → batch-sync-events (低优先级)
 *                        ↓                    ↓
 *               ImmediateConsumer      BatchConsumer
 *                        ↓                    ↓
 *                   MongoDB (实时)      MongoDB (批量)
 * 
 * 服务架构特性：
 * - 启用Kafka Streams事件路由
 * - 启用双Kafka消费者组
 * - 启用Feign客户端调用  
 * - 启用异步任务处理
 * - 启用重试机制和监控
 * 
 * 端口配置：默认8087
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@SpringBootApplication(scanBasePackages = {
    "com.pulsehub.datasync",
    "com.pulsehub.common"  // 扫描common模块的Redis工具类
})
@EnableKafka              // 启用Kafka支持
@EnableKafkaStreams       // 启用Kafka Streams事件路由
@EnableFeignClients       // 启用Feign客户端
@EnableAsync              // 启用异步任务处理
@EnableScheduling         // 启用定时任务调度
@EnableRetry              // 启用重试机制
@EnableDiscoveryClient
public class DataSyncServiceApplication {

    public static void main(String[] args) {
        // 设置时区为UTC，保证时间一致性
        System.setProperty("user.timezone", "UTC");
        
        // 启用虚拟线程(Java 21特性)，提升I/O密集型操作性能
        System.setProperty("spring.threads.virtual.enabled", "true");

        
        SpringApplication.run(DataSyncServiceApplication.class, args);
    }
}