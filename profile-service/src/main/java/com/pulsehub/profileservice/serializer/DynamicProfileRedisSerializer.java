package com.pulsehub.profileservice.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;

/**
 * DynamicUserProfile专用Redis序列化器
 * 
 * 【高级优化特性】
 * 1. 实现RedisSerializer接口，与Spring Redis深度集成
 * 2. 针对DynamicUserProfile进行序列化优化
 * 3. 更好的错误处理和性能监控
 * 4. 支持压缩和版本控制（可扩展）
 * 
 * 【性能优化】
 * - 预编译的ObjectMapper，减少初始化开销
 * - 字节级别的序列化控制
 * - 可选的数据压缩（大对象场景）
 */
@Slf4j
public class DynamicProfileRedisSerializer implements RedisSerializer<DynamicUserProfile> {

    private final ObjectMapper objectMapper;
    private static final byte[] EMPTY_ARRAY = new byte[0];
    
    // 性能监控统计
    private long serializationCount = 0;
    private long deserializationCount = 0;
    
    public DynamicProfileRedisSerializer() {
        this.objectMapper = createOptimizedObjectMapper();
        log.info("DynamicProfileRedisSerializer 已初始化");
    }

    /**
     * 创建优化的ObjectMapper
     */
    private ObjectMapper createOptimizedObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Java 8 时间支持
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(
            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
        );
        
        // 性能优化配置
        mapper.configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false
        );
        
        return mapper;
    }

    @Override
    public byte[] serialize(DynamicUserProfile profile) throws SerializationException {
        if (profile == null) {
            return EMPTY_ARRAY;
        }
        
        try {
            long startTime = System.nanoTime();
            byte[] bytes = objectMapper.writeValueAsBytes(profile);
            long endTime = System.nanoTime();
            
            serializationCount++;
            
            if (log.isDebugEnabled()) {
                log.debug("序列化用户画像 {} - 大小: {} 字节, 耗时: {} ns", 
                         profile.getUserId(), bytes.length, (endTime - startTime));
            }
            
            return bytes;
            
        } catch (JsonProcessingException e) {
            log.error("序列化DynamicUserProfile失败: userId={}", profile.getUserId(), e);
            throw new SerializationException("序列化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DynamicUserProfile deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            long startTime = System.nanoTime();
            DynamicUserProfile profile = objectMapper.readValue(bytes, DynamicUserProfile.class);
            long endTime = System.nanoTime();
            
            deserializationCount++;
            
            if (log.isDebugEnabled()) {
                log.debug("反序列化用户画像 {} - 来源大小: {} 字节, 耗时: {} ns", 
                         profile.getUserId(), bytes.length, (endTime - startTime));
            }
            
            return profile;
            
        } catch (Exception e) {
            log.error("反序列化DynamicUserProfile失败: 数据长度={}", bytes.length, e);
            throw new SerializationException("反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        return String.format("序列化次数: %d, 反序列化次数: %d", 
                           serializationCount, deserializationCount);
    }
}