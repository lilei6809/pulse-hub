package com.pulsehub.profileservice.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 用户画像系统使用示例
 * 
 * 展示如何在实际应用中使用DynamicUserProfile、DeviceClassifier和UserProfileSerializer
 * 
 * 使用场景包括：
 * - 处理用户行为事件
 * - 更新用户画像
 * - 序列化数据传输
 * - 设备类型识别
 */
@Slf4j
@Component
public class UserProfileExample {

    private final DeviceClassifier deviceClassifier;
    private final UserProfileSerializer serializer;

    @Autowired
    public UserProfileExample(DeviceClassifier deviceClassifier, UserProfileSerializer serializer) {
        this.deviceClassifier = deviceClassifier;
        this.serializer = serializer;
    }

    /**
     * 示例1：处理用户页面访问事件
     * 
     * 模拟场景：用户在移动设备上访问页面
     */
    public DynamicUserProfile handlePageViewEvent(String userId, String rawDeviceInfo) {
        log.info("处理用户 {} 的页面访问事件，设备信息: {}", userId, rawDeviceInfo);

        // 1. 分类设备类型
        DeviceClass deviceClass = deviceClassifier.classify(rawDeviceInfo);
        log.debug("设备 '{}' 被分类为: {}", rawDeviceInfo, deviceClass);

        // 2. 构建或更新用户画像
        DynamicUserProfile profile = DynamicUserProfile.builder()

                .build();

        // 3. 更新行为数据
        profile.incrementPageViewCount();           // 增加页面浏览数
        profile.updateLastActiveAt();               // 更新活跃时间
        profile.setMainDeviceClassification(deviceClass); // 设置主设备类型

        log.info("用户 {} 画像更新完成，页面浏览数: {}, 活跃程度: {}", 
                userId, profile.getPageViewCount(), profile.getActivityLevel());

        return profile;
    }

    /**
     * 示例2：批量处理多设备用户会话
     * 
     * 模拟场景：用户在多个设备上活跃
     */
    public DynamicUserProfile handleMultiDeviceSession(String userId, Set<String> deviceInfos) {
        log.info("处理用户 {} 的多设备会话，设备数量: {}", userId, deviceInfos.size());

        // 1. 批量分类设备类型
        var deviceClassifications = deviceClassifier.classifyBatch(deviceInfos);
        
        // 2. 创建用户画像
        DynamicUserProfile profile = DynamicUserProfile.builder()

                .pageViewCount(0L)
                .build();

        // 3. 处理每个设备的访问
        deviceClassifications.forEach((rawDevice, deviceClass) -> {
            log.debug("处理设备: {} -> {}", rawDevice, deviceClass);
            
            // 每个设备访问增加页面浏览数
            profile.incrementPageViewCount(5); // 模拟每个设备有5次页面访问
            
            // 添加到最近使用设备集合
            profile.addRecentDeviceType(deviceClass);
        });

        // 4. 设置主要设备（选择最常见的设备类型）
        profile.getRecentDeviceTypes().stream()
                .findFirst()
                .ifPresent(profile::setMainDeviceClassification);

        // 5. 更新活跃时间
        profile.updateLastActiveAt();

        log.info("多设备会话处理完成，用户 {} 使用了 {} 种设备类型，总页面浏览数: {}", 
                userId, profile.getRecentDeviceTypes().size(), profile.getPageViewCount());

        return profile;
    }

    /**
     * 示例3：用户画像数据传输（Kafka场景）
     * 
     * 模拟场景：将用户画像发送到Kafka消息队列
     */
    public String prepareForKafkaTransmission(DynamicUserProfile profile) {
        log.info("准备将用户 {} 的画像数据发送到Kafka", profile.getUserId());

        try {
            // 序列化为Kafka消息格式
            String kafkaJson = serializer.toKafkaJson(profile);
            
            log.debug("Kafka消息准备完成，消息大小: {} 字符", kafkaJson.length());
            log.info("用户 {} 的画像数据已准备好传输到Kafka", profile.getUserId());
            
            return kafkaJson;

        } catch (JsonProcessingException e) {
            log.error("序列化用户画像到Kafka格式失败: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize profile for Kafka", e);
        }
    }

    /**
     * 示例4：用户画像缓存存储（Redis场景）
     * 
     * 模拟场景：将用户画像存储到Redis缓存
     */
    public String prepareForRedisStorage(DynamicUserProfile profile) {
        log.info("准备将用户 {} 的画像数据存储到Redis缓存", profile.getUserId());

        try {
            // 序列化为Redis存储格式（压缩）
            String redisJson = serializer.toRedisJson(profile);
            
            log.debug("Redis存储数据准备完成，数据大小: {} 字符", redisJson.length());
            log.info("用户 {} 的画像数据已准备好存储到Redis", profile.getUserId());
            
            return redisJson;

        } catch (JsonProcessingException e) {
            log.error("序列化用户画像到Redis格式失败: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize profile for Redis", e);
        }
    }

    /**
     * 示例5：从传输格式恢复用户画像
     * 
     * 模拟场景：从Kafka消息或Redis缓存恢复用户画像对象
     */
    public DynamicUserProfile restoreFromTransmission(String kafkaJson, String redisJson) {
        log.info("从传输格式恢复用户画像数据");

        try {
            DynamicUserProfile fromKafka = null;
            DynamicUserProfile fromRedis = null;

            // 从Kafka消息恢复
            if (kafkaJson != null && !kafkaJson.isEmpty()) {
                fromKafka = serializer.fromKafkaJson(kafkaJson);
                log.debug("从Kafka消息恢复用户 {} 的画像", fromKafka.getUserId());
            }

            // 从Redis缓存恢复
            if (redisJson != null && !redisJson.isEmpty()) {
                fromRedis = serializer.fromRedisJson(redisJson);
                log.debug("从Redis缓存恢复用户 {} 的画像", fromRedis.getUserId());
            }

            // 返回其中一个（实际应用中可能需要合并逻辑）
            DynamicUserProfile result = fromKafka != null ? fromKafka : fromRedis;
            
            if (result != null) {
                log.info("用户画像恢复成功，用户ID: {}, 活跃程度: {}", 
                        result.getUserId(), result.getActivityLevel());
            }

            return result;

        } catch (JsonProcessingException e) {
            log.error("从传输格式恢复用户画像失败: {}", e.getMessage());
            throw new RuntimeException("Failed to restore profile from transmission", e);
        }
    }

    /**
     * 示例6：用户活跃度分析
     * 
     * 模拟场景：分析用户的活跃模式
     */
    public String analyzeUserActivity(DynamicUserProfile profile) {
        //log.info("分析用户 {} 的活跃模式", profile.getUserId());

        StringBuilder analysis = new StringBuilder();
        
        // 基础活跃度分析
        String activityLevel = profile.getActivityLevel();
        analysis.append(String.format("活跃程度: %s\n", activityLevel));
        
        // 页面浏览行为分析
        Long pageViews = profile.getPageViewCount();
        if (pageViews != null) {
            if (pageViews > 1000) {
                analysis.append("高活跃用户 - 页面浏览数超过1000\n");
            } else if (pageViews > 100) {
                analysis.append("中等活跃用户 - 页面浏览数在100-1000之间\n");
            } else {
                analysis.append("低活跃用户 - 页面浏览数少于100\n");
            }
        }

        // 设备使用模式分析
        Set<DeviceClass> recentDevices = profile.getRecentDeviceTypes();
        if (recentDevices != null && !recentDevices.isEmpty()) {
            analysis.append(String.format("使用设备类型数量: %d\n", recentDevices.size()));
            
            if (recentDevices.size() > 2) {
                analysis.append("多设备用户 - 跨设备使用频繁\n");
            } else if (recentDevices.contains(DeviceClass.MOBILE)) {
                analysis.append("移动优先用户 - 主要使用移动设备\n");
            } else if (recentDevices.contains(DeviceClass.DESKTOP)) {
                analysis.append("桌面优先用户 - 主要使用桌面设备\n");
            }
        }

        // 时间基础分析
        if (profile.getLastActiveAt() != null) {
            boolean isActiveRecently = profile.isActiveWithin(3600); // 1小时内
            if (isActiveRecently) {
                analysis.append("实时活跃用户 - 最近1小时内有活动\n");
            }
        }

        String result = analysis.toString();
        log.info("用户 {} 活跃模式分析完成:\n{}", profile.getUserId(), result);
        
        return result;
    }

    /**
     * 示例7：完整的事件处理流程
     * 
     * 模拟场景：从接收用户事件到完成数据处理的完整流程
     */
    public void demonstrateCompleteWorkflow() {
        log.info("=== 开始演示完整的用户画像处理流程 ===");

        try {
            // 1. 模拟接收用户事件
            String userId = "demo-user-" + System.currentTimeMillis();
            String deviceInfo = "iPhone"; // 来自前端或User-Agent解析

            // 2. 处理页面访问事件
            DynamicUserProfile profile = handlePageViewEvent(userId, deviceInfo);

            // 3. 模拟多次访问
            for (int i = 0; i < 5; i++) {
                profile.incrementPageViewCount();
                if (i % 2 == 0) {
                    profile.addRecentDeviceType(DeviceClass.DESKTOP); // 模拟切换设备
                }
            }

            // 4. 分析用户活跃度
            String analysis = analyzeUserActivity(profile);

            // 5. 准备数据传输
            String kafkaData = prepareForKafkaTransmission(profile);
            String redisData = prepareForRedisStorage(profile);

            // 6. 验证数据恢复
            DynamicUserProfile restoredProfile = restoreFromTransmission(kafkaData, redisData);

            // 7. 最终验证
            if (restoredProfile != null && restoredProfile.getUserId().equals(userId)) {
                log.info("✅ 完整流程演示成功！用户画像数据完整性验证通过");
                log.info("最终用户画像: 页面浏览数={}, 设备类型数={}, 活跃程度={}", 
                        restoredProfile.getPageViewCount(),
                        restoredProfile.getRecentDeviceTypes().size(),
                        restoredProfile.getActivityLevel());
            } else {
                log.error("❌ 完整流程演示失败！数据完整性验证未通过");
            }

        } catch (Exception e) {
            log.error("完整流程演示过程中发生异常: {}", e.getMessage(), e);
        }

        log.info("=== 完整的用户画像处理流程演示结束 ===");
    }
} 