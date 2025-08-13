package com.pulsehub.profileservice.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
public class DynamicUserProfileSerializer {

    private final ObjectMapper objectMapper;


    // 数据版本标识
    private static final String SCHEMA_VERSION_KEY = "_schema_version";
    private static final String CURRENT_SCHEMA_VERSION = "1.0";

    // 元数据字段
    private static final String SERIALIZED_AT_KEY = "_serialized_at";
    private static final String DATA_SOURCE_KEY = "_data_source";

    /**
     * 初始化序列化器
     * 配置支持 Java 8 时间类型和向后兼容性
     */
    public DynamicUserProfileSerializer() {
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


    // ==================== Kafka序列化方法 ====================

    /**
     * 将DynamicUserProfile序列化为Kafka消息JSON格式
     *
     * @param profile 用户画像对象
     * @return JSON字符串
     * @throws JsonProcessingException 序列化异常
     */
    public String toKafkaJson(DynamicUserProfile profile) throws JsonProcessingException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }

        // 创建包装对象，添加元数据
        Map<String, Object> kafkaMessage = new HashMap<>();
        kafkaMessage.put("profile", profile);
        kafkaMessage.put(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION);
        kafkaMessage.put(SERIALIZED_AT_KEY, Instant.now());
        kafkaMessage.put(DATA_SOURCE_KEY, "profile-service");

        String json = objectMapper.writeValueAsString(kafkaMessage);
        log.debug("Serialized profile {} to Kafka JSON: {} chars",
                profile.getUserId(), json.length());
        return json;
    }

    /**
     * 从Kafka消息JSON反序列化为DynamicUserProfile
     *
     * @param kafkaJson Kafka消息JSON字符串
     * @return 用户画像对象
     * @throws JsonProcessingException 反序列化异常
     */
    public DynamicUserProfile fromKafkaJson(String kafkaJson) throws JsonProcessingException {
        if (kafkaJson == null || kafkaJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Kafka JSON cannot be null or empty");
        }

        try {
            // 解析包装消息
            Map<String, Object> kafkaMessage = objectMapper.readValue(kafkaJson, Map.class);

            // 检查schema版本兼容性
            String schemaVersion = (String) kafkaMessage.get(SCHEMA_VERSION_KEY);
            if (schemaVersion != null && !isCompatibleVersion(schemaVersion)) {
                log.warn("Incompatible schema version detected: {}, current: {}",
                        schemaVersion, CURRENT_SCHEMA_VERSION);
            }

            // 提取profile数据
            Object profileData = kafkaMessage.get("profile");
            if (profileData == null) {
                throw new IllegalArgumentException("Profile data not found in Kafka message");
            }

            // 转换为DynamicUserProfile对象
            String profileJson = objectMapper.writeValueAsString(profileData);
            DynamicUserProfile profile = objectMapper.readValue(profileJson, DynamicUserProfile.class);

            log.debug("Deserialized profile {} from Kafka JSON", profile.getUserId());
            return profile;

        } catch (JsonMappingException e) {
            log.error("Failed to deserialize Kafka JSON: {}", e.getMessage());
            throw new JsonProcessingException("Invalid Kafka JSON structure: " + e.getMessage()) {};
        }
    }

    // ==================== Redis序列化方法 ====================

    /**
     * 将DynamicUserProfile序列化为Redis存储格式（压缩JSON）
     *
     * @param profile 用户画像对象
     * @return Redis存储字符串
     * @throws JsonProcessingException 序列化异常
     */
    public String toRedisJson(DynamicUserProfile profile) throws JsonProcessingException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }

        // Redis存储采用更紧凑的格式，减少存储空间
        Map<String, Object> redisData = new HashMap<>();
        redisData.put("u", profile.getUserId());                    // 用户ID
        redisData.put("la", profile.getLastActiveAt());             // 最后活跃时间
        redisData.put("pv", profile.getPageViewCount());            // 页面浏览数
        redisData.put("dc", profile.getDeviceClassification());     // 设备分类
        redisData.put("rd", profile.getRecentDeviceTypes());        // 最近设备类型
        redisData.put("v", profile.getVersion());                   // 版本号
        redisData.put("ua", profile.getUpdatedAt());                // 更新时间
        redisData.put("_sv", CURRENT_SCHEMA_VERSION);               // schema版本

        String json = objectMapper.writeValueAsString(redisData);
        log.debug("Serialized profile {} to Redis JSON: {} chars",
                profile.getUserId(), json.length());
        return json;
    }

    /**
     * 从Redis存储格式反序列化为DynamicUserProfile
     *
     * @param redisJson Redis存储JSON字符串
     * @return 用户画像对象
     * @throws JsonProcessingException 反序列化异常
     */
    @SuppressWarnings("unchecked")
    public DynamicUserProfile fromRedisJson(String redisJson) throws JsonProcessingException {
        if (redisJson == null || redisJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis JSON cannot be null or empty");
        }

        try {
            Map<String, Object> redisData = objectMapper.readValue(redisJson, Map.class);

            // 检查schema版本
            String schemaVersion = (String) redisData.get("_sv");
            if (schemaVersion != null && !isCompatibleVersion(schemaVersion)) {
                log.warn("Incompatible Redis schema version: {}", schemaVersion);
            }

            // 构建DynamicUserProfile对象
            DynamicUserProfile.DynamicUserProfileBuilder builder = DynamicUserProfile.builder()
                    .userId((String) redisData.get("u"))

                    .pageViewCount(getLongValue(redisData.get("pv")))
                    .version(getLongValue(redisData.get("v")));

            // 处理时间字段
            if (redisData.get("la") != null) {
                builder.lastActiveAt(parseInstant(redisData.get("la")));
            }
            if (redisData.get("ua") != null) {
                builder.updatedAt(parseInstant(redisData.get("ua")));
            }

            // 处理设备分类
            if (redisData.get("dc") != null) {
                builder.deviceClassification(parseDeviceClass(redisData.get("dc")));
            }

            // 处理最近设备类型集合
            if (redisData.get("rd") != null) {
                builder.recentDeviceTypes(parseDeviceClassSet(redisData.get("rd")));
            }

            DynamicUserProfile profile = builder.build();
            log.debug("Deserialized profile {} from Redis JSON", profile.getUserId());
            return profile;

        } catch (Exception e) {
            log.error("Failed to deserialize Redis JSON: {}", e.getMessage());
            throw new JsonProcessingException("Invalid Redis JSON structure: " + e.getMessage()) {};
        }
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

    private boolean isCompatibleVersion(String version) {
        // 简单的版本兼容性检查，可根据需要扩展
        return CURRENT_SCHEMA_VERSION.equals(version) || "1.0".equals(version);
    }

    /**
     * 安全获取Long值
     */
    private Long getLongValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Invalid long value: {}", value);
            return null;
        }
    }

    /**
     * 解析Instant时间
     */
    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {

            try {
                return Instant.parse((String) value);
            } catch (Exception e) {
                log.warn("Invalid Instant value: {}", value);
                return null;
            }
        }
        return null;
    }

    /**
     * 解析DeviceClass枚举
     */
    private DeviceClass parseDeviceClass(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return DeviceClass.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid DeviceClass value: {}", value);
            return DeviceClass.UNKNOWN;
        }
    }

    /**
     * 解析设备类型集合
     */
    @SuppressWarnings("unchecked")
    private java.util.Set<DeviceClass> parseDeviceClassSet(Object value) {
        if (value == null) {
            return new java.util.HashSet<>();
        }

        java.util.Set<DeviceClass> deviceTypes = new java.util.HashSet<>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                DeviceClass deviceClass = parseDeviceClass(item);
                if (deviceClass != null) {
                    deviceTypes.add(deviceClass);
                }
            }
        }
        return deviceTypes;
    }
}
