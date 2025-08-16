package com.pulsehub.datasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据同步服务主应用类
 * 
 * 核心功能：
 * 1. 混合同步策略 - 立即同步(关键业务数据) + 批量同步(普通数据)
 * 2. Redis版本控制 - 基于版本号的并发控制和数据一致性
 * 3. MongoDB增量更新 - 支持选择性字段更新和乐观锁
 * 4. DocSyncProducer定时任务 - 处理dirty flags的批量同步
 * 5. 分布式锁保护 - 保证更新操作的原子性
 * 
 * 服务架构特性：
 * - 启用Kafka消息处理
 * - 启用Feign客户端调用
 * - 启用异步任务处理
 * - 启用定时任务调度
 * - 启用重试机制
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
@EnableFeignClients       // 启用Feign客户端
@EnableAsync              // 启用异步任务处理
@EnableScheduling         // 启用定时任务调度
@EnableRetry              // 启用重试机制
public class DataSyncServiceApplication {

    public static void main(String[] args) {
        // 设置时区为UTC，保证时间一致性
        System.setProperty("user.timezone", "UTC");
        
        // 启用虚拟线程(Java 21特性)，提升I/O密集型操作性能
        System.setProperty("spring.threads.virtual.enabled", "true");
        
        SpringApplication.run(DataSyncServiceApplication.class, args);
    }
}