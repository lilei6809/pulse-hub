package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.UserProfileSnapshot;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile;
import com.pulsehub.profileservice.repository.StaticUserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 用户画像聚合服务
 * 
 * 【核心职责】
 * 1. 聚合静态和动态用户画像数据
 * 2. 提供统一的用户画像查询接口
 * 3. 优化数据获取性能和缓存策略
 * 4. 支持不同业务场景的数据需求
 * 
 * 【架构优势】
 * - 解耦：下游系统无需了解数据存储细节
 * - 性能：智能缓存和并行查询优化
 * - 扩展：易于添加新的数据源和计算逻辑
 * - 一致：统一的数据访问模式
 * 
 * 【缓存策略】
 * - 完整快照：缓存15分钟，适合CRM场景
 * - 轻量快照：缓存5分钟，适合API响应
 * - 实时快照：不缓存，适合实时分析
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileAggregationService {

    private final StaticUserProfileRepository staticProfileRepository;
    private final ProfileService dynamicProfileService;



    // ===================================================================
    // 核心聚合方法
    // ===================================================================

    /**
     * 获取完整的用户画像快照
     * 
     * 【使用场景】
     * - CRM系统查询用户详情
     * - 客服系统获取用户信息
     * - 管理后台用户管理
     * 
     * 【性能策略】
     * - 并行查询静态和动态数据
     * - 缓存完整结果15分钟
     * - 支持部分数据降级
     * 
     * @param userId 用户ID
     * @return 用户画像快照
     */
    @Cacheable(value = "crm-user-profiles", key = "#userId", unless = "#result.isEmpty()")
    public Optional<UserProfileSnapshot> getFullProfile(String userId) {
        log.info("📊 获取用户完整画像: {}", userId);

        try {
            // 并行查询静态和动态数据
            CompletableFuture<Optional<StaticUserProfile>> staticFuture =
                CompletableFuture.supplyAsync(() -> staticProfileRepository.findById(userId));
            
            CompletableFuture<Optional<DynamicUserProfile>> dynamicFuture = 
                CompletableFuture.supplyAsync(() -> getDynamicProfile(userId));

            // 等待两个查询完成
            Optional<StaticUserProfile> staticProfile = staticFuture.join();
            Optional<DynamicUserProfile> dynamicProfile = dynamicFuture.join();

            // 检查是否至少有一个数据源有数据
            if (staticProfile.isEmpty() && dynamicProfile.isEmpty()) {
                log.warn("⚠️ 用户 {} 的静态和动态画像都不存在", userId);
                return Optional.empty();
            }

            // 创建聚合快照
            UserProfileSnapshot snapshot = UserProfileSnapshot.from(
                staticProfile.orElse(null), 
                dynamicProfile.orElse(null)
            );

            log.info("✅ 成功聚合用户画像: {} (静态:{}, 动态:{})", 
                    userId, staticProfile.isPresent(), dynamicProfile.isPresent());
            
            return Optional.of(snapshot);

        } catch (Exception e) {
            log.error("❌ 获取用户画像失败: {}", userId, e);
            return Optional.empty();
        }
    }

    /**
     * 获取轻量级用户画像快照
     * 
     * 【使用场景】
     * - API响应优化
     * - 移动端数据展示
     * - 实时推荐系统
     * 
     * 【性能策略】
     * - 优先从缓存获取动态数据
     * - 减少字段传输
     * - 5分钟缓存策略
     * 
     * @param userId 用户ID
     * @return 轻量级用户画像快照
     */
    @Cacheable(value = "user-behaviors", key = "#userId", unless = "#result.isEmpty()")
    public Optional<UserProfileSnapshot> getLightProfile(String userId) {
        log.info("⚡ 获取用户轻量画像: {}", userId);

        try {
            // 先尝试获取缓存的动态数据
            Optional<DynamicUserProfile> dynamicProfile = getDynamicProfile(userId);
            
            if (dynamicProfile.isPresent()) {
                // 如果有动态数据，只获取必要的静态数据
                Optional<StaticUserProfile> staticProfile = staticProfileRepository.findById(userId);
                
                UserProfileSnapshot snapshot = UserProfileSnapshot.from(
                    staticProfile.orElse(null), 
                    dynamicProfile.get()
                );
                
                log.info("✅ 轻量画像获取成功: {}", userId);
                return Optional.of(snapshot);
            } else {
                // 如果没有动态数据，只返回静态数据
                Optional<StaticUserProfile> staticProfile = staticProfileRepository.findById(userId);
                
                if (staticProfile.isPresent()) {
                    UserProfileSnapshot snapshot = UserProfileSnapshot.fromStatic(staticProfile.get());
                    log.info("✅ 仅静态画像获取成功: {}", userId);
                    return Optional.of(snapshot);
                }
            }

            log.warn("⚠️ 用户 {} 没有任何画像数据", userId);
            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ 获取轻量画像失败: {}", userId, e);
            return Optional.empty();
        }
    }

    /**
     * 获取实时用户画像快照（不缓存）
     * 
     * 【使用场景】
     * - 实时分析场景
     * - 数据一致性要求高的场景
     * - 调试和监控场景
     * 
     * @param userId 用户ID
     * @return 实时用户画像快照
     */
    public Optional<UserProfileSnapshot> getRealtimeProfile(String userId) {
        log.info("🔄 获取用户实时画像: {}", userId);
        
        // 直接查询，不使用缓存
        return getProfileWithoutCache(userId);
    }

    // ===================================================================
    // 批量查询方法
    // ===================================================================

    /**
     * 批量获取用户画像快照
     * 
     * 【性能优化】
     * - 批量查询数据库
     * - 并行处理聚合
     * - 部分失败容错
     * 
     * @param userIds 用户ID列表
     * @return 用户画像快照列表
     */
    public List<UserProfileSnapshot> getBatchProfiles(List<String> userIds) {
        log.info("📋 批量获取用户画像: {} 个用户", userIds.size());

        try {
            // 批量查询静态数据
            List<StaticUserProfile> staticProfiles = staticProfileRepository.findAllById(userIds);
            
            // 批量查询动态数据（现在可以正确工作，因为DynamicUserProfile有userId字段）
            List<DynamicUserProfile> dynamicProfiles = userIds.stream()
                .map(this::getDynamicProfile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

            // 创建聚合结果
            return userIds.stream()
                .map(userId -> {
                    StaticUserProfile staticProfile = staticProfiles.stream()
                        .filter(p -> userId.equals(p.getUserId()))
                        .findFirst()
                        .orElse(null);
                    
                    DynamicUserProfile dynamicProfile = dynamicProfiles.stream()
                        .filter(p -> userId.equals(p.getUserId()))
                        .findFirst()
                        .orElse(null);
                    
                    if (staticProfile != null || dynamicProfile != null) {
                        return UserProfileSnapshot.from(staticProfile, dynamicProfile);
                    }
                    return null;
                })
                .filter(snapshot -> snapshot != null)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ 批量获取用户画像失败", e);
            return List.of();
        }
    }

    // ===================================================================
    // 业务查询方法
    // ===================================================================

    /**
     * 获取新用户列表
     * 
     * @param limit 限制数量
     * @return 新用户画像列表
     */
    public List<UserProfileSnapshot> getNewUsers(int limit) {
        log.info("🆕 获取新用户列表: 限制{}个", limit);
        
        List<StaticUserProfile> newStaticProfiles = staticProfileRepository.findNewUsers(limit);
        
        return newStaticProfiles.stream()
            .map(staticProfile -> {
                Optional<DynamicUserProfile> dynamicProfile = 
                    getDynamicProfile(staticProfile.getUserId());
                return UserProfileSnapshot.from(staticProfile, dynamicProfile.orElse(null));
            })
            .collect(Collectors.toList());
    }

    /**
     * 获取活跃用户列表
     * 
     * @param limit 限制数量
     * @return 活跃用户画像列表
     */
    public List<UserProfileSnapshot> getActiveUsers(int limit) {
        log.info("🔥 获取活跃用户列表: 限制{}个", limit);
        
        // 获取所有用户并根据动态画像筛选活跃用户
        List<StaticUserProfile> allUsers = staticProfileRepository.findByIsDeletedFalse();
        
        return allUsers.stream()
            .limit(limit * 3) // 多取一些以便筛选
            .map(staticProfile -> {
                Optional<DynamicUserProfile> dynamicProfile = 
                    getDynamicProfile(staticProfile.getUserId());
                if (dynamicProfile.isPresent()) {
                    UserProfileSnapshot snapshot = UserProfileSnapshot.from(staticProfile, dynamicProfile.get());
                    // 只返回活跃用户
                    if (snapshot.isActiveUser()) {
                        return snapshot;
                    }
                }
                return null;
            })
            .filter(snapshot -> snapshot != null)
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 获取高价值用户列表
     * 
     * @param limit 限制数量
     * @return 高价值用户画像列表
     */
    public List<UserProfileSnapshot> getHighValueUsers(int limit) {
        log.info("💎 获取高价值用户列表: 限制{}个", limit);
        
        // 获取完整画像的用户并筛选高价值用户
        List<StaticUserProfile> completeUsers = staticProfileRepository.findCompleteProfiles();
        
        return completeUsers.stream()
            .limit(limit * 3) // 多取一些以便筛选
            .map(staticProfile -> {
                Optional<DynamicUserProfile> dynamicProfile = 
                    getDynamicProfile(staticProfile.getUserId());
                return UserProfileSnapshot.from(staticProfile, dynamicProfile.orElse(null));
            })
            .filter(snapshot -> snapshot.isHighValueUser()) // 筛选高价值用户
            .sorted((a, b) -> Integer.compare(b.getValueScore(), a.getValueScore())) // 按价值分数排序
            .limit(limit)
            .collect(Collectors.toList());
    }

    // ===================================================================
    // 辅助方法
    // ===================================================================

    /**
     * 将 UserProfile 转换为 DynamicUserProfile
     * 这是一个临时的适配器方法，用于处理类型不匹配问题
     * 
     * 【重要修正】
     * 添加了userId字段的正确设置，确保聚合操作能正常工作
     */
    private Optional<DynamicUserProfile> getDynamicProfile(String userId) {
        return dynamicProfileService.getProfile(userId)
            .map(userProfile -> {
                // 这里需要实现从 UserProfile 到 DynamicUserProfile 的转换
                // 由于 UserProfile 字段有限，我们创建一个基础的 DynamicUserProfile
                return DynamicUserProfile.builder()
                    .userId(userId) // 🔥 重要：设置用户ID，确保数据关联正确
                    .lastActiveAt(userProfile.getLastSeenAt().toInstant(java.time.ZoneOffset.UTC))
                    .pageViewCount(0L) // 默认值，因为 UserProfile 中没有这个字段
                    .updatedAt(java.time.Instant.now()) // 设置更新时间
                    .version(1L) // 设置默认版本
                    .build();
            });
    }

    /**
     * 获取用户画像但不使用缓存
     */
    private Optional<UserProfileSnapshot> getProfileWithoutCache(String userId) {
        try {
            Optional<StaticUserProfile> staticProfile = staticProfileRepository.findById(userId);
            Optional<DynamicUserProfile> dynamicProfile = getDynamicProfile(userId);

            if (staticProfile.isEmpty() && dynamicProfile.isEmpty()) {
                return Optional.empty();
            }

            UserProfileSnapshot snapshot = UserProfileSnapshot.from(
                staticProfile.orElse(null), 
                dynamicProfile.orElse(null)
            );

            return Optional.of(snapshot);

        } catch (Exception e) {
            log.error("❌ 获取用户画像失败: {}", userId, e);
            return Optional.empty();
        }
    }

    /**
     * 检查用户是否存在（任一数据源）
     */
    public boolean userExists(String userId) {
        return staticProfileRepository.existsById(userId) || 
               getDynamicProfile(userId).isPresent();
    }

    /**
     * 获取用户画像数据新鲜度信息
     */
    public ProfileFreshnessInfo getProfileFreshness(String userId) {
        Optional<StaticUserProfile> staticProfile = staticProfileRepository.findById(userId);
        Optional<DynamicUserProfile> dynamicProfile = getDynamicProfile(userId);
        
        return ProfileFreshnessInfo.builder()
            .userId(userId)
            .hasStaticData(staticProfile.isPresent())
            .hasDynamicData(dynamicProfile.isPresent())
            .staticLastUpdated(staticProfile.map(StaticUserProfile::getUpdatedAt).orElse(null))
            .dynamicLastUpdated(dynamicProfile.map(DynamicUserProfile::getUpdatedAt).orElse(null))
            .build();
    }

    /**
     * 画像数据新鲜度信息
     */
    @lombok.Builder
    @lombok.Data
    public static class ProfileFreshnessInfo {
        private String userId;
        private boolean hasStaticData;
        private boolean hasDynamicData;
        private java.time.Instant staticLastUpdated;
        private java.time.Instant dynamicLastUpdated;
        
        public boolean isDataFresh(long maxAgeMinutes) {
            java.time.Instant threshold = java.time.Instant.now().minusSeconds(maxAgeMinutes * 60);
            
            boolean staticFresh = !hasStaticData || 
                (staticLastUpdated != null && staticLastUpdated.isAfter(threshold));
            boolean dynamicFresh = !hasDynamicData || 
                (dynamicLastUpdated != null && dynamicLastUpdated.isAfter(threshold));
            
            return staticFresh && dynamicFresh;
        }
    }

    // ===================================================================
    // 缓存管理方法
    // ===================================================================

    /**
     * 清除指定用户的聚合缓存
     * 
     * @param userId 用户ID
     */
    @org.springframework.cache.annotation.CacheEvict(
        value = {"crm-user-profiles", "user-behaviors", "analytics-user-profiles"}, 
        key = "#userId"
    )
    public void evictUserCaches(String userId) {
        log.info("🗑️ 清除用户聚合缓存: {}", userId);
    }

    /**
     * 清除所有聚合缓存
     */
    @org.springframework.cache.annotation.CacheEvict(
        value = {"crm-user-profiles", "user-behaviors", "analytics-user-profiles"}, 
        allEntries = true
    )
    public void evictAllCaches() {
        log.info("🗑️ 清除所有聚合缓存");
    }

    // ===================================================================
    // 服务状态和监控
    // ===================================================================

    /**
     * 获取聚合服务状态
     * 
     * @return 服务状态信息
     */
    public ServiceStatus getServiceStatus() {
        ServiceStatus status = new ServiceStatus();
        
        try {
            // 检查静态数据源
            long staticCount = staticProfileRepository.count();
            status.setStaticDataSourceHealthy(true);
            status.setStaticUserCount(staticCount);
        } catch (Exception e) {
            status.setStaticDataSourceHealthy(false);
            status.addError("静态数据源异常: " + e.getMessage());
        }
        
        try {
            // 检查动态数据源
            boolean dynamicHealthy = dynamicProfileService.profileExists("health-check");
            status.setDynamicDataSourceHealthy(true);
        } catch (Exception e) {
            status.setDynamicDataSourceHealthy(false);
            status.addError("动态数据源异常: " + e.getMessage());
        }
        
        status.setOverallHealthy(status.isStaticDataSourceHealthy() && 
                               status.isDynamicDataSourceHealthy());
        
        log.debug("📊 聚合服务状态检查完成: {}", status.isOverallHealthy() ? "健康" : "异常");
        
        return status;
    }

    /**
     * 服务状态信息
     */
    @lombok.Data
    public static class ServiceStatus {
        private boolean overallHealthy;
        private boolean staticDataSourceHealthy;
        private boolean dynamicDataSourceHealthy;
        private long staticUserCount;
        private final List<String> errors = new java.util.ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
    }
}