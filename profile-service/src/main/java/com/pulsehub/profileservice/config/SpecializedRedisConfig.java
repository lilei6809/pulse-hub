package com.pulsehub.profileservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 专用RedisTemplate配置方案
 * 
 * 【设计思路】
 * 为不同的数据类型创建专用的RedisTemplate，
 * 实现类型安全和序列化优化的平衡
 */
@Configuration
public class SpecializedRedisConfig {

    /**
     * DynamicUserProfile专用的RedisTemplate
     * 
     * 【优势】
     * - 类型安全：RedisTemplate<String, DynamicUserProfile>
     * - 序列化优化：针对DynamicUserProfile优化
     * - 职责单一：只处理动态用户画像
     */
    @Bean("dynamicProfileRedisTemplate")
    public RedisTemplate<String, DynamicUserProfile> dynamicProfileRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        
        RedisTemplate<String, DynamicUserProfile> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Key序列化
        template.setKeySerializer(new StringRedisSerializer());
        
        // Value序列化：专门为DynamicUserProfile配置
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(
            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
        );
        
        GenericJackson2JsonRedisSerializer serializer = 
            new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
}