package com.pulsehub.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * PulseHub服务发现中心
 * 
 * 基于Eureka实现服务发现功能，允许本地开发环境与Docker环境中的服务无缝通信
 * 服务器默认运行在8761端口
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
} 