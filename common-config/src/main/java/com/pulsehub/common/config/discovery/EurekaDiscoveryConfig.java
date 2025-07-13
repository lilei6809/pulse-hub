package com.pulsehub.common.config.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

/**
 * 通用Eureka客户端配置类
 * 
 * 此类通过@EnableDiscoveryClient注解启用服务发现功能
 * 配置为有条件加载，可以通过pulsehub.discovery.enabled=false禁用
 * 
 * 服务发现功能允许本地开发环境和Docker环境中的服务互相发现并通信
 */
@Configuration
@EnableDiscoveryClient
@ConditionalOnProperty(name = "pulsehub.discovery.enabled", havingValue = "true", matchIfMissing = true)
public class EurekaDiscoveryConfig {

    private static final Logger logger = LoggerFactory.getLogger(EurekaDiscoveryConfig.class);

    @Value("${spring.application.name:unknown-service}")
    private String serviceName;

    @Value("${eureka.client.service-url.defaultZone:http://localhost:8761/eureka}")
    private String eurekaServiceUrl;

    private final Environment environment;

    public EurekaDiscoveryConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * 初始化时记录服务发现配置信息
     */
    @PostConstruct
    public void init() {
        String activeProfiles = String.join(", ", environment.getActiveProfiles());
        
        logger.info("Service Discovery enabled for {} service", serviceName);
        logger.info("Eureka server URL: {}", eurekaServiceUrl);
        logger.info("Active profiles: {}", activeProfiles);
    }
} 