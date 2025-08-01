package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.DeviceClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * DynamicProfileService 使用示例
 * 
 * 展示如何在实际业务场景中使用动态用户画像服务
 * 包括常见的用户行为记录、查询和分析操作
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicProfileUsageExample {

    private final DynamicProfileService dynamicProfileService;

    /**
     * 示例1：用户访问页面时的处理流程
     * 这是最常见的高频操作场景
     */
    public void handleUserPageView(String userId, DeviceClass deviceClass) {
        log.info("📄 处理用户页面访问: userId={}, device={}", userId, deviceClass);
        
        try {
            // 1. 记录页面浏览（自动更新活跃时间）
            DynamicUserProfile profile = dynamicProfileService.recordPageView(userId);
            
            // 2. 如果有设备信息，更新设备分类
            if (deviceClass != null && deviceClass != DeviceClass.UNKNOWN) {
                profile = dynamicProfileService.updateDeviceInfo(userId, deviceClass);
            }
            
            log.info("✅ 页面访问处理完成: userId={}, 总浏览数={}, 活跃等级={}", 
                    userId, profile.getPageViewCount(), profile.getActivityLevel());
                    
        } catch (Exception e) {
            log.error("❌ 处理用户页面访问失败: userId={}", userId, e);
        }
    }

    /**
     * 示例2：批量处理用户行为数据
     * 适用于从Kafka消息队列批量消费数据的场景
     */
    public CompletableFuture<Void> batchProcessUserActions(Map<String, Long> userPageViews) {
        log.info("📦 开始批量处理用户行为数据: {} 个用户", userPageViews.size());
        
        return dynamicProfileService.batchUpdatePageViews(userPageViews)
                .thenAccept(updateCount -> {
                    log.info("✅ 批量处理完成: 成功更新 {} 个用户画像", updateCount);
                })
                .exceptionally(throwable -> {
                    log.error("❌ 批量处理失败", throwable);
                    return null;
                });
    }

    /**
     * 示例3：实时活跃用户分析
     * 用于运营监控和实时推荐场景
     */
    public void analyzeActiveUsers() {
        log.info("📊 开始实时活跃用户分析");
        
        // 获取最近1小时活跃用户
        List<DynamicUserProfile> activeUsers1h = dynamicProfileService.getActiveUsers(3600);
        
        // 获取最近24小时活跃用户
        List<DynamicUserProfile> activeUsers24h = dynamicProfileService.getActiveUsers(24 * 3600);
        
        // 分析用户活跃度分布
        Map<String, Long> activityDistribution = new HashMap<>();
        for (DynamicUserProfile profile : activeUsers24h) {
            String level = profile.getActivityLevel();
            activityDistribution.merge(level, 1L, Long::sum);
        }
        
        log.info("📊 活跃用户分析结果:");
        log.info("   - 1小时内活跃: {} 人", activeUsers1h.size());
        log.info("   - 24小时内活跃: {} 人", activeUsers24h.size());
        log.info("   - 活跃度分布: {}", activityDistribution);
        
        // 找出最活跃的用户（页面浏览数最多）
        Optional<DynamicUserProfile> mostActiveUser = activeUsers24h.stream()
                .max(Comparator.comparing(profile -> 
                        profile.getPageViewCount() != null ? profile.getPageViewCount() : 0L));
                        
        if (mostActiveUser.isPresent()) {
            DynamicUserProfile profile = mostActiveUser.get();
            log.info("🏆 最活跃用户: {} (页面浏览: {}次)", 
                    profile.getUserId(), profile.getPageViewCount());
        }
    }

    /**
     * 示例4：设备使用情况分析
     * 用于了解用户设备偏好和优化产品体验
     */
    public void analyzeDeviceUsage() {
        log.info("📱 开始设备使用情况分析");
        
        // 获取设备分布统计
        Map<DeviceClass, Long> deviceDistribution = dynamicProfileService.getDeviceDistribution();
        
        log.info("📊 设备分布统计:");
        deviceDistribution.forEach((device, count) -> {
            log.info("   - {}: {} 用户", device, count);
        });
        
        // 分析主要设备类型的用户行为
        for (DeviceClass deviceClass : Arrays.asList(DeviceClass.MOBILE, DeviceClass.DESKTOP, DeviceClass.TABLET)) {
            List<DynamicUserProfile> deviceUsers = dynamicProfileService.getUsersByDeviceClass(deviceClass);
            
            if (!deviceUsers.isEmpty()) {
                double avgPageViews = deviceUsers.stream()
                        .mapToLong(profile -> profile.getPageViewCount() != null ? profile.getPageViewCount() : 0L)
                        .average()
                        .orElse(0.0);
                        
                log.info("📱 {} 用户行为: {} 人，平均页面浏览 {:.1f} 次", 
                        deviceClass, deviceUsers.size(), avgPageViews);
            }
        }
    }

    /**
     * 示例5：高价值用户识别
     * 用于精准营销和用户分层
     */
    public List<DynamicUserProfile> identifyHighValueUsers(long minPageViews) {
        log.info("🎯 开始识别高价值用户: 最小页面浏览数 {}", minPageViews);
        
        // 获取高参与度用户
        List<DynamicUserProfile> highEngagementUsers = 
                dynamicProfileService.getHighEngagementUsers(minPageViews);
        
        // 按页面浏览数排序
        highEngagementUsers.sort((p1, p2) -> 
                Long.compare(
                        p2.getPageViewCount() != null ? p2.getPageViewCount() : 0L,
                        p1.getPageViewCount() != null ? p1.getPageViewCount() : 0L
                ));
        
        log.info("🎯 高价值用户识别完成: 找到 {} 个用户", highEngagementUsers.size());
        
        // 输出Top 10用户信息
        int topCount = Math.min(10, highEngagementUsers.size());
        for (int i = 0; i < topCount; i++) {
            DynamicUserProfile profile = highEngagementUsers.get(i);
            log.info("   {}. userId: {}, 页面浏览: {}次, 活跃等级: {}, 设备: {}", 
                    i + 1,
                    profile.getUserId(),
                    profile.getPageViewCount(),
                    profile.getActivityLevel(),
                    profile.getDeviceClassification());
        }
        
        return highEngagementUsers;
    }

    /**
     * 示例6：用户画像完整性检查和数据清理
     * 用于数据质量监控和维护
     */
    public void performDataMaintenanceCheck() {
        log.info("🔧 开始数据维护检查");
        
        // 获取统计信息
        DynamicProfileService.ActivityStatistics stats = 
                dynamicProfileService.getActivityStatistics();
        
        log.info("📊 当前系统状态:");
        log.info("   - 总用户数: {}", stats.getTotalUsers());
        log.info("   - 24小时活跃用户: {}", stats.getActiveUsers24h());
        log.info("   - 1小时活跃用户: {}", stats.getActiveUsers1h());
        log.info("   - 24小时活跃率: {:.2f}%", stats.getActivityRate24h());
        
        // 清理过期数据
        dynamicProfileService.cleanupExpiredData();
        
        log.info("✅ 数据维护检查完成");
    }

    /**
     * 示例7：用户个性化推荐数据准备
     * 为推荐系统提供用户行为数据
     */
    public Map<String, Object> prepareRecommendationData(String userId) {
        log.debug("🎯 准备用户推荐数据: {}", userId);
        
        Optional<DynamicUserProfile> profileOpt = dynamicProfileService.getProfile(userId);
        if (profileOpt.isEmpty()) {
            log.warn("⚠️ 用户动态画像不存在: {}", userId);
            return new HashMap<>();
        }
        
        DynamicUserProfile profile = profileOpt.get();
        
        Map<String, Object> recommendationData = new HashMap<>();
        recommendationData.put("userId", userId);
        recommendationData.put("activityLevel", profile.getActivityLevel());
        recommendationData.put("pageViewCount", profile.getPageViewCount());
        recommendationData.put("primaryDevice", profile.getDeviceClassification());
        recommendationData.put("recentDevices", profile.getRecentDeviceTypes());
        recommendationData.put("lastActiveAt", profile.getLastActiveAt());
        
        // 计算用户活跃天数
        if (profile.getLastActiveAt() != null) {
            long hoursAgo = java.time.Duration.between(profile.getLastActiveAt(), Instant.now()).toHours();
            recommendationData.put("hoursInactive", hoursAgo);
            recommendationData.put("isRecentlyActive", hoursAgo <= 24);
        }
        
        log.debug("✅ 推荐数据准备完成: {} (活跃等级: {})", userId, profile.getActivityLevel());
        return recommendationData;
    }

    /**
     * 示例8：A/B测试用户分群
     * 根据用户行为特征进行分群
     */
    public Map<String, List<String>> segmentUsersForABTest() {
        log.info("🧪 开始A/B测试用户分群");
        
        Map<String, List<String>> segments = new HashMap<>();
        segments.put("高活跃用户", new ArrayList<>());
        segments.put("中等活跃用户", new ArrayList<>());
        segments.put("低活跃用户", new ArrayList<>());
        segments.put("移动端用户", new ArrayList<>());
        segments.put("桌面端用户", new ArrayList<>());
        
        // 获取活跃用户进行分群
        List<DynamicUserProfile> activeUsers = dynamicProfileService.getActiveUsers(7 * 24 * 3600); // 7天内活跃
        
        for (DynamicUserProfile profile : activeUsers) {
            String userId = profile.getUserId();
            
            // 按活跃程度分群
            String activityLevel = profile.getActivityLevel();
            switch (activityLevel) {
                case "VERY_ACTIVE":
                case "ACTIVE":
                    segments.get("高活跃用户").add(userId);
                    break;
                case "LESS_ACTIVE":
                    segments.get("中等活跃用户").add(userId);
                    break;
                default:
                    segments.get("低活跃用户").add(userId);
                    break;
            }
            
            // 按设备类型分群
            if (profile.getDeviceClassification() == DeviceClass.MOBILE) {
                segments.get("移动端用户").add(userId);
            } else if (profile.getDeviceClassification() == DeviceClass.DESKTOP) {
                segments.get("桌面端用户").add(userId);
            }
        }
        
        log.info("🧪 A/B测试分群完成:");
        segments.forEach((segmentName, userIds) -> {
            log.info("   - {}: {} 人", segmentName, userIds.size());
        });
        
        return segments;
    }
}