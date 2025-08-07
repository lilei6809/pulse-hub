package com.pulsehub.profileservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * 测试环境专用Redis配置
 * 
 * 【简化设计】
 * 由于生产代码已使用DynamicProfileSerializer解决序列化问题，
 * 测试配置只需要提供正确的Redis连接即可。
 * 
 * 特性：
 * - 动态端口配置，支持嵌入式Redis
 * - 简化配置，依赖生产代码的序列化器
 * - 避免Bean定义冲突
 */
@TestConfiguration
public class TestRedisConfig {

    /**
     * 为测试环境配置Redis连接工厂
     * 使用嵌入式Redis的端口配置
     */
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6370}") int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.setValidateConnection(false); // 测试环境不验证连接
        factory.afterPropertiesSet(); // 确保连接工厂初始化
        return factory;
    }
} 