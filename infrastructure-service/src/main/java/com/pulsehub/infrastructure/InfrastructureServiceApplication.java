package com.pulsehub.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.event.EventListener;

/**
 * PulseHub 基础设施服务
 * 负责管理整个数据平台的基础设施配置：
 * - Kafka Topics 创建和管理
 * - 数据库连接验证
 * - Redis 连接验证
 * - Schema Registry 健康检查
 * - 服务发现注册与管理
 */
@Slf4j
@SpringBootApplication
@EnableDiscoveryClient  // 启用服务发现客户端功能，注册到Eureka服务器
public class InfrastructureServiceApplication {

    public static void main(String[] args) {
        log.info("🚀 Starting PulseHub Infrastructure Service...");
        SpringApplication.run(InfrastructureServiceApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("✅ Infrastructure Service is ready! All platform components initialized.");
        log.info("🔍 Service registered with Service Discovery - available for other services to discover");
    }
} 