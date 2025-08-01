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
 * 用户画像序列化工具类
 * 
 * 负责DynamicUserProfile在不同系统间的数据转换：
 * - Kafka消息序列化/反序列化（JSON格式）
 * - Redis存储序列化/反序列化（压缩JSON格式）
 * - 支持数据版本演进和向后兼容
 * 
 * 设计原则：
 * - 统一序列化格式确保数据一致性
 * - 包含版本信息支持schema evolution
 * - 优雅处理null值和缺失字段
 * - 支持压缩存储（Redis场景）
 */
@Slf4j
@Component
public class UserProfileSerializer {

    private final ObjectMapper objectMapper;
    
    // 数据版本标识
    private static final String SCHEMA_VERSION_KEY = "_schema_version";
    private static final String CURRENT_SCHEMA_VERSION = "1.0";
    
    // 元数据字段
    private static final String SERIALIZED_AT_KEY = "_serialized_at";
    private static final String DATA_SOURCE_KEY = "_data_source";

    public UserProfileSerializer() {
        this.objectMapper = new ObjectMapper();
        // 注册Java 8时间模块支持Instant等类型
        this.objectMapper.registerModule(new JavaTimeModule());
        // 忽略未知属性，支持向后兼容
        this.objectMapper.configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, 
            false
        );
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

    // ==================== 通用序列化方法 ====================

    /**
     * 通用JSON序列化（用于调试、日志等）
     * 
     * @param profile 用户画像对象
     * @return 格式化的JSON字符串
     */
    public String toFormattedJson(DynamicUserProfile profile) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize profile to formatted JSON: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 从通用JSON反序列化
     * 
     * @param json JSON字符串
     * @return 用户画像对象
     */
    public DynamicUserProfile fromJson(String json) throws JsonProcessingException {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON cannot be null or empty");
        }
        return objectMapper.readValue(json, DynamicUserProfile.class);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 检查schema版本兼容性
     */
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