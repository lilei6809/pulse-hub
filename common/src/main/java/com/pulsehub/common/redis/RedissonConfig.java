package com.pulsehub.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Redisson配置类
 * 
 * 提供Redisson客户端配置，用于分布式锁、缓存等Redis高级功能
 * 支持单机和集群模式，自动根据配置选择连接方式
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:3000}")
    private int timeout;

    /**
     * 创建Redisson客户端
     * 
     * @return RedissonClient实例
     */
    @Bean
    @Primary
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        // 构建Redis连接地址
        String address = String.format("redis://%s:%d", redisHost, redisPort);
        
        // 配置单机模式
        config.useSingleServer()
            .setAddress(address)
            .setDatabase(database)
            .setConnectTimeout(timeout)
            .setConnectionMinimumIdleSize(1)
            .setConnectionPoolSize(10)
            .setIdleConnectionTimeout(30000)
            .setKeepAlive(true)
            .setRetryAttempts(3)
            .setRetryInterval(1500);
        
        // 设置密码（如果提供）
        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            config.useSingleServer().setPassword(redisPassword);
        }
        
        // 设置看门狗超时时间（锁的默认过期时间）
        config.setLockWatchdogTimeout(30000); // 30秒
        
        log.info("初始化Redisson客户端: address={}, database={}", address, database);
        
        return Redisson.create(config);
    }
}