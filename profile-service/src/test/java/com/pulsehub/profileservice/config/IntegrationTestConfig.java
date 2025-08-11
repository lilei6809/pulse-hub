package com.pulsehub.profileservice.config;

import com.pulsehub.profileservice.domain.DynamicUserProfileSerializer;
import com.pulsehub.profileservice.factory.DynamicUserProfileFactory;
import com.pulsehub.profileservice.service.DynamicProfileService;
import com.pulsehub.profileservice.repository.StaticUserProfileRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 集成测试专用配置
 * 独立的配置避免与其他测试类的 Bean 定义冲突
 */
@TestConfiguration
public class IntegrationTestConfig {

    @MockBean
    private StaticUserProfileRepository staticUserProfileRepository;

    @MockBean
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * Redis 连接工厂配置
     */
    @Bean("testRedisConnectionFactory")
    public RedisConnectionFactory integrationTestRedisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6370}") int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.setValidateConnection(false);
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * Redis 模板配置
     */
    @Bean("testRedisTemplate")
    @Primary
    public RedisTemplate<String, Object> integrationTestRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 设置序列化器
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * DynamicProfileSerializer Bean
     */
    @Bean
    public DynamicUserProfileSerializer dynamicProfileSerializer() {
        return new DynamicUserProfileSerializer();
    }

    /**
     * DynamicProfileService Bean
     */
    @Bean
    public DynamicProfileService dynamicProfileService(
            @Qualifier("testRedisTemplate") RedisTemplate<String, Object> redisTemplate,
            StaticUserProfileRepository staticProfileRepository,
            ApplicationEventPublisher eventPublisher,
            DynamicUserProfileSerializer dynamicUserProfileSerializer,
            DynamicUserProfileFactory factory) {
        return new DynamicProfileService(
            redisTemplate, 
            staticProfileRepository, 
            eventPublisher,
                dynamicUserProfileSerializer,
                factory
        );
    }
}