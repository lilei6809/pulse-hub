package com.pulsehub.profileservice.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

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
        return (builder) -> builder
                .withCacheConfiguration("user-profiles", // 4. 针对名为 "user-profiles" 的缓存进行特定配置
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofHours(1))); // 5. 将这个特定缓存的过期时间设为 1 小时
    }
}