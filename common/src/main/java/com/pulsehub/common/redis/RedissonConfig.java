package com.pulsehub.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
    
    @Value("${spring.data.redis.connect-timeout:3000}")
    private int connectTimeout;

    /**
     * 创建确定性序列化的ObjectMapper
     */
    @Bean
    public ObjectMapper deterministicObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // 注册JavaTime模块支持Instant等时间类型
        mapper.registerModule(new JavaTimeModule());
        
        // 关键配置：确保序列化的确定性
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);  // 按字母顺序排序属性
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true); // Map键按字母排序
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false); // 时间不写成时间戳
        mapper.configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false); // 不使用纳秒
        
        return mapper;
    }

    /**
     * 创建Redisson客户端
     * 
     * @return RedissonClient实例
     */
    @Bean
    @Primary
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        // 使用确定性序列化的JsonJacksonCodec
        config.setCodec(new JsonJacksonCodec(deterministicObjectMapper()));
        
        // 构建Redis连接地址
        String address = String.format("redis://%s:%d", redisHost, redisPort);
        
        // 配置单机模式
        config.useSingleServer()
            .setAddress(address)
            .setDatabase(database)
            .setConnectTimeout(connectTimeout)
            .setTimeout(timeout)
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
        
        log.info("初始化Redisson客户端(确定性序列化): address={}, database={}", address, database);
        
        return Redisson.create(config);
    }
}