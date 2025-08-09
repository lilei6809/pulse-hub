package com.pulsehub.profileservice.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.pulsehub.profileservice.controller.dto.CreateDynamicUserProfileRequest;
import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.DeviceClassifier;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.DynamicUserProfileSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 动态用户画像工厂类
 * 
 * 【设计目标】
 * - 集成设备分类和序列化功能，简化业务代码
 * - 提供多种创建方式，满足不同业务场景需求
 * - 确保数据完整性和业务逻辑的正确执行
 * - 为复杂对象创建提供统一入口点
 * 
 * 【核心价值】
 * 1. 业务逻辑集中：将设备分类、数据验证等逻辑集中处理
 * 2. 代码简化：Controller和Service层只需调用工厂方法
 * 3. 依赖管理：统一管理DeviceClassifier和Serializer的依赖
 * 4. 扩展性：未来添加新的创建方式无需修改现有代码
 * 
 * 【使用场景】
 * - REST API请求处理：从CreateDynamicUserProfileRequest创建
 * - 批量数据处理：从Map数据创建
 * - 事件处理：从Kafka消息创建
 * - 数据迁移：从JSON反序列化创建
 * 
 * @author PulseHub Team
 * @version 1.0
 * @since 2025-01-09
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicUserProfileFactory {

    // 核心依赖组件
    private final DeviceClassifier deviceClassifier;
    private final DynamicUserProfileSerializer serializer;

    // ===================================================================
    // 从请求对象创建方法
    // ===================================================================

    /**
     * 从API请求创建动态用户画像
     * 
     * 这是最常用的工厂方法，处理来自REST API的创建请求
     * 
     * @param request API请求对象
     * @return 完整的动态用户画像对象
     * @throws IllegalArgumentException 当请求数据无效时
     */
    public DynamicUserProfile createFromRequest(CreateDynamicUserProfileRequest request) {
        // 参数验证
        if (request == null) {
            throw new IllegalArgumentException("创建请求不能为null");
        }
        if (request.getUserId() == null || request.getUserId().trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        // 设备分类处理
        DeviceClass deviceClass = deviceClassifier.classify(request.getDevice());
        log.debug("设备分类完成: '{}' -> {}", request.getDevice(), deviceClass);

        // 构建画像对象
        DynamicUserProfile profile = DynamicUserProfile.builder()
                .userId(request.getUserId().trim())
                .pageViewCount(request.getPageViewCount() != null ? request.getPageViewCount() : 0L)
                .deviceClassification(deviceClass)
                .rawDeviceInput(request.getDevice())
                .recentDeviceTypes(createDeviceTypeSet(deviceClass))
                .lastActiveAt(Instant.now())
                .version(1L)
                .updatedAt(Instant.now())
                .build();

        log.info("✨ 通过工厂创建动态用户画像: {} (设备: {}, 页面浏览: {})", 
                profile.getUserId(), deviceClass, profile.getPageViewCount());

        return profile;
    }

    /**
     * 从现有画像更新设备信息
     * 
     * 用于当用户切换设备或上报新的设备信息时
     * 
     * @param existingProfile 现有画像
     * @param newDeviceInput 新的设备输入
     * @return 更新后的画像对象
     */
    public DynamicUserProfile updateDeviceInformation(DynamicUserProfile existingProfile, String newDeviceInput) {
        if (existingProfile == null) {
            throw new IllegalArgumentException("现有画像不能为null");
        }

        DeviceClass newDeviceClass = deviceClassifier.classify(newDeviceInput);
        
        // 更新设备信息
        existingProfile.updateDeviceInformation(newDeviceClass, newDeviceInput);
        
        log.debug("🔄 更新用户 {} 的设备信息: '{}' -> {}", 
                existingProfile.getUserId(), newDeviceInput, newDeviceClass);

        return existingProfile;
    }

    // ===================================================================
    // 从数据Map创建方法（适用于批量处理）
    // ===================================================================

    /**
     * 从数据Map创建动态用户画像
     * 
     * 适用于批量数据处理、数据导入等场景
     * 
     * @param dataMap 包含用户数据的Map
     * @return 动态用户画像对象
     */
    public DynamicUserProfile createFromMap(Map<String, Object> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            throw new IllegalArgumentException("数据Map不能为空");
        }

        String userId = getStringValue(dataMap, "userId");
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        // 提取和处理数据
        String rawDevice = getStringValue(dataMap, "device");
        Long pageViews = getLongValue(dataMap, "pageViewCount");
        DeviceClass deviceClass = deviceClassifier.classify(rawDevice);

        return DynamicUserProfile.builder()
                .userId(userId.trim())
                .pageViewCount(pageViews != null ? pageViews : 0L)
                .deviceClassification(deviceClass)
                .rawDeviceInput(rawDevice)
                .recentDeviceTypes(createDeviceTypeSet(deviceClass))
                .lastActiveAt(Instant.now())
                .version(1L)
                .updatedAt(Instant.now())
                .build();
    }

    // ===================================================================
    // 从JSON创建方法（整合序列化器）
    // ===================================================================

    /**
     * 从JSON字符串创建动态用户画像
     * 
     * 整合了序列化器的功能，提供统一的反序列化入口
     * 
     * @param json JSON字符串
     * @return 动态用户画像对象，如果反序列化失败返回null
     */
    public DynamicUserProfile createFromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            log.warn("⚠️ 尝试从空JSON创建画像");
            return null;
        }

        DynamicUserProfile profile = serializer.deserialize(json);
        if (profile != null) {
            log.debug("📄 从JSON成功创建画像: {}", profile.getUserId());
        } else {
            log.error("❌ 从JSON创建画像失败");
        }
        
        return profile;
    }

    /**
     * 将动态用户画像序列化为JSON
     * 
     * 整合了序列化器功能，提供统一的序列化入口
     * 
     * @param profile 动态用户画像对象
     * @return JSON字符串，如果序列化失败返回null
     */
    public String toJson(DynamicUserProfile profile) {
        if (profile == null) {
            log.warn("⚠️ 尝试序列化空画像对象");
            return null;
        }

        String json = serializer.serialize(profile);
        if (json != null) {
            log.debug("📄 画像序列化成功: {} (JSON长度: {})", profile.getUserId(), json.length());
        } else {
            log.error("❌ 画像序列化失败: {}", profile.getUserId());
        }
        
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


        DynamicUserProfile profile = serializer.fromKafkaJson(kafkaJson);


        if (profile != null) {
            log.debug("📄 从KafkaJSON成功创建画像: {}", profile.getUserId());
        } else {
            log.error("❌ 从KafkaJSON创建画像失败");
        }

        return profile;

    }

    /**
     * 将DynamicUserProfile序列化为Kafka消息JSON格式
     *
     * @param profile 用户画像对象
     * @return JSON字符串
     * @throws JsonProcessingException 序列化异常
     */
    public String toKafkaJson(DynamicUserProfile profile) throws JsonProcessingException {

        String kafkaJson = serializer.toKafkaJson(profile);

        if (kafkaJson != null) {
            log.debug("📄 创建 kafkaJson 成功: {}", kafkaJson);
        } else {
            log.error("创建 KafkaJson 失败");
        }

        return kafkaJson;
    }



    // ===================================================================
    // 专用创建方法
    // ===================================================================

    /**
     * 创建最小化的动态用户画像
     * 
     * 仅包含必要信息，用于快速创建和性能优化场景
     * 
     * @param userId 用户ID
     * @return 最小化的动态用户画像
     */
    public DynamicUserProfile createMinimalDynamicUserProfile(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        return DynamicUserProfile.builder()
                .userId(userId.trim())
                .pageViewCount(0L)
                .deviceClassification(DeviceClass.UNKNOWN)
                .recentDeviceTypes(new HashSet<>())
                .lastActiveAt(Instant.now())
                .version(1L)
                .updatedAt(Instant.now())
                .build();
    }

    /**
     * 创建用于页面浏览记录的动态画像
     * 
     * 专门用于处理页面浏览事件的场景
     * 
     * @param userId 用户ID
     * @param initialPageViews 初始页面浏览数
     * @return 动态用户画像对象
     */
    public DynamicUserProfile createForPageViewTracking(String userId, Long initialPageViews) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        Long pageViews = initialPageViews != null && initialPageViews >= 0 ? initialPageViews : 0L;

        return DynamicUserProfile.builder()
                .userId(userId.trim())
                .pageViewCount(pageViews)
                .deviceClassification(DeviceClass.UNKNOWN)  // 页面浏览场景可能暂时不知道设备
                .recentDeviceTypes(new HashSet<>())
                .lastActiveAt(Instant.now())
                .version(1L)
                .updatedAt(Instant.now())
                .build();
    }

    // ===================================================================
    // 辅助方法
    // ===================================================================

    /**
     * 创建设备类型集合
     * 如果设备类型不是UNKNOWN，则添加到集合中
     */
    private Set<DeviceClass> createDeviceTypeSet(DeviceClass deviceClass) {
        Set<DeviceClass> deviceTypes = new HashSet<>();
        if (deviceClass != null && deviceClass != DeviceClass.UNKNOWN) {
            deviceTypes.add(deviceClass);
        }
        return deviceTypes;
    }

    /**
     * 从Map中安全获取String值
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString().trim() : null;
    }

    /**
     * 从Map中安全获取Long值
     */
    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            log.warn("⚠️ 无法转换为Long: key={}, value={}", key, value);
            return null;
        }
    }

    // ===================================================================
    // 工厂状态和配置方法
    // ===================================================================

    /**
     * 检查工厂是否已正确初始化
     * 
     * @return true如果所有依赖都已正确注入
     */
    public boolean isInitialized() {
        boolean initialized = deviceClassifier != null && serializer != null;
        if (!initialized) {
            log.error("❌ 工厂未正确初始化: deviceClassifier={}, serializer={}", 
                     deviceClassifier != null, serializer != null);
        }
        return initialized;
    }

    /**
     * 获取工厂配置信息
     * 用于调试和监控
     */
    public String getFactoryInfo() {
        return String.format("DynamicUserProfileFactory{deviceClassifier=%s, serializer=%s, initialized=%s}", 
                            deviceClassifier != null ? deviceClassifier.getClass().getSimpleName() : "null",
                            serializer != null ? serializer.getClass().getSimpleName() : "null",
                            isInitialized());
    }

    /**
     * 批量创建动态用户画像
     * 
     * @param requests 请求列表
     * @return 创建成功的画像列表
     */
    public java.util.List<DynamicUserProfile> createBatch(java.util.List<CreateDynamicUserProfileRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        java.util.List<DynamicUserProfile> profiles = new java.util.ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (CreateDynamicUserProfileRequest request : requests) {
            try {
                DynamicUserProfile profile = createFromRequest(request);
                profiles.add(profile);
                successCount++;
            } catch (Exception e) {
                log.error("❌ 批量创建中单个请求失败: userId={}, error={}", 
                         request != null ? request.getUserId() : "null", e.getMessage());
                failCount++;
            }
        }

        log.info("📦 批量创建完成: 成功={}, 失败={}, 总数={}", successCount, failCount, requests.size());
        return profiles;
    }
}