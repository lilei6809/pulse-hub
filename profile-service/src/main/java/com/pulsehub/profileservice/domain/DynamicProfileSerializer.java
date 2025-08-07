package com.pulsehub.profileservice.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 动态用户画像专用序列化器
 * 
 * 【设计目标】
 * - 专门处理 DynamicUserProfile 对象的 Redis 序列化/反序列化
 * - 解决 Java 8 时间类型（Instant）的序列化问题
 * - 保持与 RedisTemplate 的兼容性
 * - 提供高性能的序列化操作
 * 
 * 【技术特点】
 * 1. 支持 Java 8 时间类型：配置了 JavaTimeModule
 * 2. 类型安全：直接序列化/反序列化为 DynamicUserProfile 对象
 * 3. 错误处理：优雅处理序列化异常
 * 4. 日志记录：便于问题诊断和性能监控
 * 
 * 【使用场景】
 * - DynamicProfileService 中的 Redis 数据存储
 * - 确保数据类型的正确序列化和反序列化
 * - 避免 LinkedHashMap 类型转换问题
 * 
 * @author PulseHub Team
 * @version 1.0
 * @since 2025-01-07
 */
@Slf4j
@Component
public class DynamicProfileSerializer {

    private final ObjectMapper objectMapper;

    /**
     * 初始化序列化器
     * 配置支持 Java 8 时间类型和向后兼容性
     */
    public DynamicProfileSerializer() {
        this.objectMapper = new ObjectMapper();
        
        // 注册 Java 8 时间模块，支持 Instant、LocalDateTime 等
        this.objectMapper.registerModule(new JavaTimeModule());
        
        // 禁用时间戳格式，使用 ISO-8601 字符串格式（更易读）
        this.objectMapper.disable(
            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
        );
        
        // 忽略未知属性，支持向后兼容
        this.objectMapper.configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, 
            false
        );
        
        log.debug("DynamicProfileSerializer 已初始化，支持 Java 8 时间类型");
    }

    /**
     * 将 DynamicUserProfile 对象序列化为 JSON 字符串
     * 
     * @param profile 动态用户画像对象
     * @return JSON 字符串，如果序列化失败返回 null
     * @throws IllegalArgumentException 如果 profile 为 null
     */
    public String serialize(DynamicUserProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("DynamicUserProfile 不能为 null");
        }

        try {
            String json = objectMapper.writeValueAsString(profile);
            log.debug("成功序列化用户画像: {} (JSON长度: {} 字符)", 
                     profile.getUserId(), json.length());
            return json;
            
        } catch (JsonProcessingException e) {
            log.error("序列化用户画像失败: userId={}, error={}", 
                     profile.getUserId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为 DynamicUserProfile 对象
     * 
     * @param json JSON 字符串
     * @return DynamicUserProfile 对象，如果反序列化失败返回 null
     * @throws IllegalArgumentException 如果 json 为 null 或空
     */
    public DynamicUserProfile deserialize(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON 字符串不能为 null 或空");
        }

        try {
            DynamicUserProfile profile = objectMapper.readValue(json, DynamicUserProfile.class);
            log.debug("成功反序列化用户画像: {} (JSON长度: {} 字符)", 
                     profile.getUserId(), json.length());
            return profile;
            
        } catch (JsonProcessingException e) {
            log.error("反序列化用户画像失败: json={}, error={}", 
                     json.substring(0, Math.min(100, json.length())), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 序列化为字节数组（用于 Redis 二进制存储）
     * 
     * @param profile 动态用户画像对象
     * @return 字节数组，如果序列化失败返回 null
     */
    public byte[] serializeToBytes(DynamicUserProfile profile) {
        String json = serialize(profile);
        if (json != null) {
            return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 从字节数组反序列化（用于 Redis 二进制存储）
     * 
     * @param bytes 字节数组
     * @return DynamicUserProfile 对象，如果反序列化失败返回 null
     */
    public DynamicUserProfile deserializeFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("字节数组不能为 null 或空");
        }
        
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return deserialize(json);
    }

    /**
     * 获取配置好的 ObjectMapper 实例
     * 
     * @return 配置了 Java 8 时间支持的 ObjectMapper
     */
    public ObjectMapper getObjectMapper() {
        return this.objectMapper;
    }

    /**
     * 验证 JSON 字符串是否可以正确反序列化
     * 
     * @param json JSON 字符串
     * @return true 如果可以正确反序列化，false 否则
     */
    public boolean isValidJson(String json) {
        try {
            deserialize(json);
            return true;
        } catch (Exception e) {
            log.debug("JSON 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 格式化输出 DynamicUserProfile（用于调试和日志）
     * 
     * @param profile 动态用户画像对象
     * @return 格式化的 JSON 字符串
     */
    public String toFormattedJson(DynamicUserProfile profile) {
        if (profile == null) {
            return "null";
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                             .writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            log.error("格式化输出失败: {}", e.getMessage());
            return "{\"error\": \"序列化失败\"}";
        }
    }
}
