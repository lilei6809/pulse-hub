package com.pulsehub.profileservice.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备分类器
 * 
 * 实现"内部enum + 外部string配置"的hybrid设计方案：
 * - 外部传入自由字符串（如前端formFactor、User-Agent解析结果）
 * - 内部映射到受控的DeviceClass枚举
 * - 未知设备类型记录到Redis用于人工review
 * 
 * 设计思想：
 * - 系统robust：新设备不会导致程序报错
 * - 数据质量：保证落库字段始终是可控枚举
 * - 可演进性：容易添加新枚举而不需大改代码
 */
@Slf4j
@Component
public class DeviceClassifier {

    private static final String UNKNOWN_DEVICE_TYPES_KEY = "unknown_device_types";

    /**
     * 设备类型映射表
     * key: 外部传入的原始设备标识（小写）
     * value: 内部DeviceClass枚举
     */
    private static final Map<String, DeviceClass> DEVICE_MAPPING = new ConcurrentHashMap<>();

    static {
        // 移动设备映射
        DEVICE_MAPPING.put("mobile", DeviceClass.MOBILE);
        DEVICE_MAPPING.put("iphone", DeviceClass.MOBILE);
        DEVICE_MAPPING.put("android", DeviceClass.MOBILE);
        DEVICE_MAPPING.put("phone", DeviceClass.MOBILE);
        DEVICE_MAPPING.put("smartphone", DeviceClass.MOBILE);
        DEVICE_MAPPING.put("ios", DeviceClass.MOBILE);

        // 桌面设备映射
        DEVICE_MAPPING.put("desktop", DeviceClass.DESKTOP);
        DEVICE_MAPPING.put("mac", DeviceClass.DESKTOP);
        DEVICE_MAPPING.put("windows", DeviceClass.DESKTOP);
        DEVICE_MAPPING.put("pc", DeviceClass.DESKTOP);
        DEVICE_MAPPING.put("computer", DeviceClass.DESKTOP);
        DEVICE_MAPPING.put("macos", DeviceClass.DESKTOP);
        DEVICE_MAPPING.put("linux", DeviceClass.DESKTOP);

        // 平板设备映射
        DEVICE_MAPPING.put("tablet", DeviceClass.TABLET);
        DEVICE_MAPPING.put("ipad", DeviceClass.TABLET);
        DEVICE_MAPPING.put("tab", DeviceClass.TABLET);

        // 智能电视映射
        DEVICE_MAPPING.put("tv", DeviceClass.SMART_TV);
        DEVICE_MAPPING.put("smart_tv", DeviceClass.SMART_TV);
        DEVICE_MAPPING.put("smarttv", DeviceClass.SMART_TV);
        DEVICE_MAPPING.put("television", DeviceClass.SMART_TV);

        // 其他设备映射
        DEVICE_MAPPING.put("other", DeviceClass.OTHER);
        DEVICE_MAPPING.put("unknown", DeviceClass.UNKNOWN);
    }

    private final RedisTemplate<String, Object> redisTemplate;

    public DeviceClassifier(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将外部设备标识分类为内部DeviceClass枚举
     * 
     * @param rawDeviceType 外部传入的设备标识（如前端formFactor或UA解析结果）
     * @return 分类后的DeviceClass枚举
     */
    public DeviceClass classify(String rawDeviceType) {
        // 空值检查
        if (rawDeviceType == null || rawDeviceType.trim().isEmpty()) {
            log.debug("Empty device type received, returning UNKNOWN");
            return DeviceClass.UNKNOWN;
        }

        // 转换为小写进行匹配
        String normalizedType = rawDeviceType.toLowerCase().trim();
        
        // 查找映射
        DeviceClass deviceClass = DEVICE_MAPPING.get(normalizedType);

        // deviceClass != null, 说明 DEVICE_MAPPING 中匹配成功
        // 否则 deviceClass == null, 设备为 unknown
        if (deviceClass != null) {
            log.debug("Device type '{}' classified as '{}'", rawDeviceType, deviceClass);
            return deviceClass;
        } else {
            // 未知设备类型处理
            log.warn("Unknown device type received: '{}', recording for review", rawDeviceType);
            auditUnknownDeviceType(rawDeviceType);
            return DeviceClass.UNKNOWN;
        }
    }

    /**
     * 批量分类设备类型
     * 
     * @param rawDeviceTypes 设备类型列表
     * @return 分类结果Map
     */
    public Map<String, DeviceClass> classifyBatch(Set<String> rawDeviceTypes) {
        Map<String, DeviceClass> results = new ConcurrentHashMap<>();
        
        if (rawDeviceTypes != null) {
            rawDeviceTypes.forEach(rawType -> 
                results.put(rawType, classify(rawType))
            );
        }
        
        return results;
    }

    /**
     * 记录未知设备类型到Redis
     * 使用SET数据结构避免重复值堆积
     * 
     * @param rawDeviceType 未知的设备类型
     */
    private void auditUnknownDeviceType(String rawDeviceType) {
        try {
            // 使用Redis SET结构存储，自动去重
            redisTemplate.opsForSet().add(UNKNOWN_DEVICE_TYPES_KEY, rawDeviceType);
            log.debug("Recorded unknown device type '{}' to Redis for review", rawDeviceType);
        } catch (Exception e) {
            log.error("Failed to record unknown device type '{}' to Redis: {}", 
                     rawDeviceType, e.getMessage());
        }
    }

    /**
     * 获取所有未知设备类型（供运营人员review）
     * 
     * @return 未知设备类型集合
     */
    public Set<Object> getUnknownDeviceTypes() {
        try {
            return redisTemplate.opsForSet().members(UNKNOWN_DEVICE_TYPES_KEY);
        } catch (Exception e) {
            log.error("Failed to retrieve unknown device types from Redis: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * 清空未知设备类型记录（运营处理完成后调用）
     */
    public void clearUnknownDeviceTypes() {
        try {
            redisTemplate.delete(UNKNOWN_DEVICE_TYPES_KEY);
            log.info("Cleared unknown device types cache");
        } catch (Exception e) {
            log.error("Failed to clear unknown device types cache: {}", e.getMessage());
        }
    }

    /**
     * 动态添加新的设备映射（供运营人员扩展）
     * 
     * @param rawDeviceType 原始设备标识
     * @param deviceClass 目标设备分类
     */
    public void addDeviceMapping(String rawDeviceType, DeviceClass deviceClass) {
        if (rawDeviceType != null && !rawDeviceType.trim().isEmpty() && deviceClass != null) {
            String normalizedType = rawDeviceType.toLowerCase().trim();
            DEVICE_MAPPING.put(normalizedType, deviceClass);
            log.info("Added device mapping: '{}' -> '{}'", rawDeviceType, deviceClass);
        }
    }

    /**
     * 获取当前支持的设备映射（用于调试和运营查看）
     * 
     * @return 当前设备映射表的副本
     */
    public Map<String, DeviceClass> getCurrentMappings() {
        return Map.copyOf(DEVICE_MAPPING);
    }

    /**
     * 检查设备类型是否已知
     * 
     * @param rawDeviceType 设备类型
     * @return 是否已知
     */
    public boolean isKnownDeviceType(String rawDeviceType) {
        if (rawDeviceType == null || rawDeviceType.trim().isEmpty()) {
            return false;
        }
        return DEVICE_MAPPING.containsKey(rawDeviceType.toLowerCase().trim());
    }
} 