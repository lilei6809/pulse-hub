package com.pulsehub.profileservice.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 测试环境专用Redis配置
 * 
 * 为测试环境提供Redis配置，支持以下模式：
 * 1. 如果本地有Redis服务器运行，使用本地Redis
 * 2. 如果没有Redis服务器，测试会使用MockBean
 * 
 * 特性：
 * - 测试专用的Redis连接配置
 * - 优化的序列化配置
 * - 容错机制，避免测试因Redis连接失败而中断
 */
@TestConfiguration
@Profile("test")
public class TestRedisConfig {

    /**
     * 为测试环境配置Redis连接工厂
     * 使用本地Redis（如果可用）
     */
    @Bean
    @Primary
    public RedisConnectionFactory testRedisConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.setValidateConnection(false); // 测试环境不验证连接
        return factory;
    }

    /**
     * 为测试环境配置RedisTemplate
     * 配置了适合测试的序列化器
     */
    @Bean
    @Primary  // @Primary 注解是 Spring Framework 提供的一种机制，用于当多个候选 Bean 存在时，指定默认注入的 Bean。
    public RedisTemplate<String, Object> testRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用String序列化器作为key序列化器
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // 使用JSON序列化器作为value序列化器
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
} 