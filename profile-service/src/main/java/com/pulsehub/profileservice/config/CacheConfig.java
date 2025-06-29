package com.pulsehub.profileservice.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.Random;

@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15)) // 1. 设置默认的缓存过期时间为 15 分钟
                .disableCachingNullValues()       // 2. 为了防止偶然错误导致的数据不一致, 不缓存 null 值
                .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())); // 3. 设置值的序列化器为 JSON
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) -> {
            // 原有的用户画像缓存配置
            builder.withCacheConfiguration("user-profiles",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofHours(1)));

            // 🎯 CRM/CDP 分层缓存策略配置
            
            // CRM操作缓存：销售、营销、客服场景 - 实时性优先
            builder.withCacheConfiguration("crm-user-profiles",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofMinutes(10))  // 短TTL，确保数据新鲜度
                            .disableCachingNullValues()         // 不缓存空值，新用户立即可见
                            .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .computePrefixWith(cacheName -> "pulsehub:crm:" + cacheName + ":"));

            // 数据分析缓存：报表、分析场景 - 稳定性优先
            builder.withCacheConfiguration("analytics-user-profiles",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofHours(4))      // 长TTL，减少DB压力
                            // 注意：这里允许缓存null值，防止分析任务被无效查询影响
                            .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .computePrefixWith(cacheName -> "pulsehub:analytics:" + cacheName + ":"));

            // 🎯 其他业务场景的缓存配置示例
            
            // 用户行为缓存：中等时效性
            builder.withCacheConfiguration("user-behaviors",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofMinutes(30))   // 中等TTL
                            .disableCachingNullValues()         // 不缓存空值
                            .computePrefixWith(cacheName -> "pulsehub:behavior:" + cacheName + ":"));

            // 系统配置缓存：长期缓存
            builder.withCacheConfiguration("system-configs",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(Duration.ofHours(24))     // 长TTL
                            .computePrefixWith(cacheName -> "pulsehub:config:" + cacheName + ":"));
        };
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用String序列化器作为key的序列化器
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // 使用JSON序列化器作为value的序列化器
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 🌟 改进版缓存配置示例
     * 
     * 解决缓存三大问题的高级配置：
     * 1. 防缓存雪崩：随机化TTL
     * 2. 防缓存击穿：启用同步模式
     * 3. 监控友好：添加统计信息
     */
    /*
    @Bean
    public RedisCacheConfiguration advancedCacheConfiguration() {
        // 基础配置
        Duration baseTtl = Duration.ofMinutes(15);
        // 随机化TTL，防止缓存雪崩（在基础TTL的基础上±20%随机）
        Duration randomizedTtl = Duration.ofMinutes(15 + new Random().nextInt(6) - 3);
        
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(randomizedTtl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                // 启用统计信息，便于监控
                .computePrefixWith(cacheName -> "pulsehub:cache:" + cacheName + ":");
    }

    @Bean 
    public RedisCacheManagerBuilderCustomizer advancedCacheManagerCustomizer() {
        return (builder) -> {
            // 用户画像缓存：长时间缓存+随机化
            Duration userProfileTtl = Duration.ofMinutes(60 + new Random().nextInt(20) - 10);
            builder.withCacheConfiguration("user-profiles-advanced",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(userProfileTtl)
                            .disableCachingNullValues()
                            .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
            );
            
            // 短时效缓存：适用于频繁变化的数据
            Duration shortTtl = Duration.ofMinutes(5 + new Random().nextInt(4) - 2);
            builder.withCacheConfiguration("short-lived-cache",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .entryTtl(shortTtl)
                            .disableCachingNullValues()
            );
        };
    }
    */

    /**
     * 🎯 PulseHub CRM/CDP 缓存策略总结
     * 
     * 【业务驱动的缓存设计原则】
     * 1. 销售场景：实时性 > 性能，不缓存空值，短TTL
     * 2. 营销场景：平衡实时性和性能，选择性缓存
     * 3. 分析场景：性能 > 实时性，缓存空值，长TTL
     * 4. 客服场景：数据准确性最重要，实时查询
     * 
     * 【缓存层次划分】
     * - crm-user-profiles: 10分钟TTL，不缓存空值，支持销售、营销、客服
     * - analytics-user-profiles: 4小时TTL，缓存空值，支持报表分析
     * - user-behaviors: 30分钟TTL，不缓存空值，支持行为分析
     * - system-configs: 24小时TTL，缓存所有值，支持系统配置
     * 
     * 【监控和维护】
     * - 使用不同的缓存前缀便于监控和调试
     * - 定期清理缓存，避免内存泄漏
     * - 监控命中率，调整TTL和策略
     * 
     * 【扩展性考虑】
     * - 支持未来添加更多业务场景的缓存配置
     * - 支持A/B测试不同的缓存策略
     * - 支持动态调整缓存参数
     */
}