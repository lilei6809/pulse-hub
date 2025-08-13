package com.pulsehub.profileservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

@EnableDiscoveryClient
@EnableCaching
@EnableScheduling
@EnableRetry
@EnableTransactionManagement
@SpringBootApplication
public class ProfileServiceApplication {

    /**
     * 配置应用使用UTC时区
     * 确保所有时间操作都基于UTC标准
     */
    @PostConstruct
    public void configureTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.out.println("✅ 应用时区已设置为UTC");
    }

    public static void main(String[] args) {
        SpringApplication.run(ProfileServiceApplication.class, args);
    }

} 