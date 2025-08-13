package com.pulsehub.profileservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 优化的Redis配置
 * 
 * 【架构优化目标】
 * 1. 统一序列化配置，消除多个序列化器
 * 2. 在RedisTemplate层面支持Java 8时间类型
 * 3. 保持类型安全，避免手动序列化
 * 4. 性能优化，减少双重序列化开销
 * 
 * 【设计原则】
 * - 配置优于编码：在配置层解决序列化问题
 * - 单一职责：RedisTemplate负责所有序列化
 * - 类型安全：保持强类型操作
 * - 性能第一：避免不必要的序列化开销
 */
@Configuration
public class OptimizedRedisConfig {

    /**
     * 优化的ObjectMapper，支持Java 8时间类型
     * 
     * 统一配置，供所有序列化场景使用
     */
    @Bean
    @Primary
    public ObjectMapper redisObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // 注册Java 8时间模块
        objectMapper.registerModule(new JavaTimeModule());
        
        // 禁用时间戳格式，使用ISO-8601字符串
        objectMapper.disable(
            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
        );
        
        // 忽略未知属性，支持向后兼容
        objectMapper.configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, 
            false
        );
        
        return objectMapper;
    }

    /**
     * 优化的RedisTemplate配置
     * 
     * 【关键改进】
     * 1. 使用统一的ObjectMapper，支持Java 8时间类型
     * 2. 替换原有的CacheConfig中的redisTemplate
     * 3. 保持类型安全的操作体验
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> OptRedisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Key序列化：字符串
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Value序列化：支持Java 8时间的JSON序列化器
        GenericJackson2JsonRedisSerializer serializer = 
            new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
}