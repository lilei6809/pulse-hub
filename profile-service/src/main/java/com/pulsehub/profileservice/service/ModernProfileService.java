package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.UserProfileSnapshot;
import com.pulsehub.profileservice.entity.StaticUserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 现代化用户画像服务
 * 
 * 【设计理念】
 * 采用门面模式(Facade Pattern)，为上层应用提供统一的用户画像操作接口
 * 内部整合StaticProfileService和ProfileAggregationService的功能
 * 
 * 【核心优势】
 * 1. 统一接口：上层调用者无需了解内部的服务分工
 * 2. 智能路由：根据业务场景自动选择最优的数据获取策略
 * 3. 缓存优化：针对不同使用场景提供差异化的缓存策略
 * 4. 降级处理：当聚合服务不可用时，自动降级到静态数据
 * 
 * 【与旧版ProfileService对比】
 * - 旧版：基于简单UserProfile实体，功能单一
 * - 新版：基于完整用户画像体系，支持静态+动态数据聚合
 * - 旧版：单一缓存策略
 * - 新版：多场景差异化缓存策略
 * - 旧版：无版本控制
 * - 新版：支持乐观锁和数据版本管理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModernProfileService {

    private final StaticProfileService staticProfileService;
    private final ProfileAggregationService profileAggregationService;

    // ===================================================================
    // 核心用户画像操作 - 门面模式
    // ===================================================================

    /**
     * 获取完整用户画像快照
     * 优先使用聚合服务，失败时降级到静态数据
     * 
     * @param userId 用户ID
     * @return 用户画像快照
     */
    public Optional<UserProfileSnapshot> getUserProfile(String userId) {
        log.debug("🎯 获取用户完整画像: {}", userId);
        
        try {
            // 优先尝试获取完整的聚合画像
            Optional<UserProfileSnapshot> snapshot = profileAggregationService.getFullProfile(userId);
            if (snapshot.isPresent()) {
                log.debug("✅ 成功获取聚合画像: {}", userId);
                return snapshot;
            }
        } catch (Exception e) {
            log.warn("⚠️ 聚合服务异常，降级到静态数据: {} - {}", userId, e.getMessage());
        }

        // 降级：仅返回静态画像数据
        return staticProfileService.getProfile(userId)
                .map(UserProfileSnapshot::fromStatic)
                .map(snapshot -> {
                    log.debug("📊 降级返回静态画像: {}", userId);
                    return snapshot;
                });
    }

    /**
     * CRM场景：获取用户画像
     * 快速响应，实时性优先
     */
    @Cacheable(value = "crm-user-profiles", key = "#userId", unless = "#result.isEmpty()")
    public Optional<UserProfileSnapshot> getUserProfileForCRM(String userId) {
        log.debug("🎯 CRM场景获取用户画像: {}", userId);
        
        try {
            return profileAggregationService.getFullProfile(userId);
        } catch (Exception e) {
            log.warn("CRM场景降级到静态数据: {} - {}", userId, e.getMessage());
            return staticProfileService.getProfileForCRM(userId)
                    .map(UserProfileSnapshot::fromStatic);
        }
    }

    /**
     * Analytics场景：获取用户画像
     * 允许缓存空值，长期缓存
     */
    @Cacheable(value = "analytics-user-profiles", key = "#userId")
    public Optional<UserProfileSnapshot> getUserProfileForAnalytics(String userId) {
        log.debug("📊 Analytics场景获取用户画像: {}", userId);
        
        try {
            return profileAggregationService.getFullProfile(userId);
        } catch (Exception e) {
            log.warn("Analytics场景降级到静态数据: {} - {}", userId, e.getMessage());
            return staticProfileService.getProfileForAnalytics(userId)
                    .map(UserProfileSnapshot::fromStatic);
        }
    }

    // ===================================================================
    // 静态画像管理 - 委托给StaticProfileService
    // ===================================================================

    /**
     * 创建新用户的静态画像
     * 
     * @param staticProfile 静态画像数据
     * @return 创建后的画像
     */
    public StaticUserProfile createStaticProfile(StaticUserProfile staticProfile) {
        log.info("🆕 创建新用户静态画像: {}", staticProfile.getUserId());
        return staticProfileService.createProfile(staticProfile);
    }

    /**
     * 更新用户的静态画像
     * 
     * @param staticProfile 要更新的画像数据
     * @return 更新后的画像
     */
    public StaticUserProfile updateStaticProfile(StaticUserProfile staticProfile) {
        log.info("🔄 更新用户静态画像: {}", staticProfile.getUserId());
        return staticProfileService.updateProfile(staticProfile);
    }

    /**
     * 部分更新用户画像
     * 
     * @param userId 用户ID
     * @param updates 要更新的字段
     * @return 更新后的画像
     */
    public Optional<StaticUserProfile> partialUpdateStaticProfile(String userId, StaticUserProfile updates) {
        log.info("🔧 部分更新用户画像: {}", userId);
        return staticProfileService.partialUpdate(userId, updates);
    }

    /**
     * 获取静态用户画像
     * 
     * @param userId 用户ID
     * @return 静态画像
     */
    public Optional<StaticUserProfile> getStaticProfile(String userId) {
        return staticProfileService.getProfile(userId);
    }

    // ===================================================================
    // 业务查询方法 - 增强版
    // ===================================================================

    /**
     * 根据邮箱获取用户画像快照
     */
    public Optional<UserProfileSnapshot> getUserProfileByEmail(String email) {
        log.debug("📧 根据邮箱获取用户画像: {}", email);
        
        return staticProfileService.getProfileByEmail(email)
                .flatMap(staticProfile -> {
                    String userId = staticProfile.getUserId();
                    return getUserProfile(userId);
                });
    }

    /**
     * 根据手机号获取用户画像快照
     */
    public Optional<UserProfileSnapshot> getUserProfileByPhoneNumber(String phoneNumber) {
        log.debug("📱 根据手机号获取用户画像: {}", phoneNumber);
        
        return staticProfileService.getProfileByPhoneNumber(phoneNumber)
                .flatMap(staticProfile -> {
                    String userId = staticProfile.getUserId();
                    return getUserProfile(userId);
                });
    }

    /**
     * 批量获取用户画像快照
     * 
     * @param userIds 用户ID列表
     * @return 用户画像快照列表
     */
    public List<UserProfileSnapshot> getUserProfiles(List<String> userIds) {
        log.debug("📦 批量获取用户画像: {} 个用户", userIds.size());
        
        return userIds.stream()
                .map(this::getUserProfile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * 获取新用户画像快照
     * 
     * @param days 注册天数内
     * @return 新用户画像列表
     */
    public List<UserProfileSnapshot> getNewUserProfiles(int days) {
        log.debug("👶 获取新用户画像（{}天内）", days);
        
        List<StaticUserProfile> newUsers = staticProfileService.getNewUsers(days);
        return newUsers.stream()
                .map(StaticUserProfile::getUserId)
                .map(this::getUserProfile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * 根据来源渠道获取用户画像
     */
    public List<UserProfileSnapshot> getUserProfilesBySourceChannel(String sourceChannel) {
        log.debug("📣 根据来源渠道获取用户画像: {}", sourceChannel);
        
        List<StaticUserProfile> users = staticProfileService.getUsersBySourceChannel(sourceChannel);
        return users.stream()
                .map(StaticUserProfile::getUserId)
                .map(this::getUserProfile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    // ===================================================================
    // 数据验证和检查
    // ===================================================================

    /**
     * 检查用户是否存在
     */
    public boolean userExists(String userId) {
        return staticProfileService.profileExists(userId);
    }

    /**
     * 检查邮箱是否已被使用
     */
    public boolean emailExists(String email) {
        return staticProfileService.emailExists(email);
    }

    /**
     * 检查手机号是否已被使用
     */
    public boolean phoneNumberExists(String phoneNumber) {
        return staticProfileService.phoneNumberExists(phoneNumber);
    }

    /**
     * 验证用户画像数据
     */
    public StaticProfileService.ProfileValidationResult validateProfile(StaticUserProfile staticProfile) {
        return staticProfileService.validateProfile(staticProfile);
    }

    // ===================================================================
    // 软删除管理
    // ===================================================================

    /**
     * 软删除用户画像
     */
    public boolean softDeleteUser(String userId) {
        log.info("🗑️ 软删除用户: {}", userId);
        return staticProfileService.softDeleteProfile(userId);
    }

    /**
     * 恢复已删除的用户画像
     */
    public boolean restoreUser(String userId) {
        log.info("🔄 恢复用户: {}", userId);
        return staticProfileService.restoreProfile(userId);
    }

    /**
     * 获取已删除的用户画像
     */
    public List<UserProfileSnapshot> getDeletedUserProfiles() {
        log.debug("🗑️ 获取已删除的用户画像");
        
        List<StaticUserProfile> deletedUsers = staticProfileService.getDeletedProfiles();
        return deletedUsers.stream()
                .map(UserProfileSnapshot::fromStatic)
                .collect(Collectors.toList());
    }

    // ===================================================================
    // 缓存管理
    // ===================================================================

    /**
     * 清除用户的所有缓存
     */
    public void evictUserCaches(String userId) {
        log.info("🗑️ 清除用户缓存: {}", userId);
        staticProfileService.evictUserCaches(userId);
        profileAggregationService.evictUserCaches(userId);
    }

    /**
     * 清除所有用户画像缓存
     */
    public void evictAllCaches() {
        log.info("🗑️ 清除所有用户画像缓存");
        staticProfileService.evictAllCaches();
        profileAggregationService.evictAllCaches();
    }

    // ===================================================================
    // 统计和报告
    // ===================================================================

    /**
     * 获取用户画像统计信息
     */
    public StaticProfileService.ProfileStatistics getProfileStatistics() {
        return staticProfileService.getProfileStatistics();
    }

    /**
     * 获取高价值用户画像
     * 基于用户画像快照的价值评分
     */
    public List<UserProfileSnapshot> getHighValueUsers(int limit) {
        log.debug("💎 获取高价值用户 (top {})", limit);
        
        // 先获取完整画像的用户
        List<StaticUserProfile> completeProfiles = staticProfileService.getCompleteProfiles();
        
        return completeProfiles.stream()
                .map(StaticUserProfile::getUserId)
                .map(this::getUserProfile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(UserProfileSnapshot::isHighValueUser)
                .sorted((a, b) -> Integer.compare(b.getValueScore(), a.getValueScore()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取活跃用户画像
     */
    public List<UserProfileSnapshot> getActiveUsers(int limit) {
        log.debug("🔥 获取活跃用户 (top {})", limit);
        
        List<StaticUserProfile> allUsers = staticProfileService.getCompleteProfiles();
        
        return allUsers.stream()
                .map(StaticUserProfile::getUserId)
                .map(this::getUserProfile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(UserProfileSnapshot::isActiveUser)
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ===================================================================
    // 兼容性方法 - 为了平滑迁移
    // ===================================================================

    /**
     * 兼容性方法：创建用户画像
     * 映射到新的createStaticProfile方法
     * 
     * @deprecated 建议使用 createStaticProfile 方法
     */
    @Deprecated
    public StaticUserProfile createProfile(String userId) {
        log.warn("⚠️ 使用了已废弃的createProfile方法，请升级到createStaticProfile");
        
        StaticUserProfile profile = StaticUserProfile.builder()
                .userId(userId)
                .build();
        
        return createStaticProfile(profile);
    }

    /**
     * 兼容性方法：根据ID获取画像
     * 映射到新的getUserProfile方法
     * 
     * @deprecated 建议使用 getUserProfile 方法获取完整画像快照
     */
    @Deprecated
    public Optional<StaticUserProfile> getProfile(String userId) {
        log.warn("⚠️ 使用了已废弃的getProfile方法，建议升级到getUserProfile");
        return getStaticProfile(userId);
    }

    // ===================================================================
    // 健康检查和监控
    // ===================================================================

    /**
     * 服务健康检查
     * 验证各个依赖服务是否正常工作
     */
    public HealthStatus getHealthStatus() {
        HealthStatus status = new HealthStatus();
        
        try {
            // 检查静态画像服务
            staticProfileService.getProfileStatistics();
            status.setStaticProfileServiceHealthy(true);
        } catch (Exception e) {
            status.setStaticProfileServiceHealthy(false);
            status.addError("StaticProfileService异常: " + e.getMessage());
        }
        
        try {
            // 检查聚合服务
            profileAggregationService.getServiceStatus();
            status.setAggregationServiceHealthy(true);
        } catch (Exception e) {
            status.setAggregationServiceHealthy(false);
            status.addError("ProfileAggregationService异常: " + e.getMessage());
        }
        
        status.setOverallHealthy(status.isStaticProfileServiceHealthy() && 
                               status.isAggregationServiceHealthy());
        
        return status;
    }

    // ===================================================================
    // 内部类：健康状态
    // ===================================================================

    public static class HealthStatus {
        private boolean overallHealthy;
        private boolean staticProfileServiceHealthy;
        private boolean aggregationServiceHealthy;
        private final List<String> errors = new java.util.ArrayList<>();

        // Getters and Setters
        public boolean isOverallHealthy() { return overallHealthy; }
        public void setOverallHealthy(boolean overallHealthy) { this.overallHealthy = overallHealthy; }

        public boolean isStaticProfileServiceHealthy() { return staticProfileServiceHealthy; }
        public void setStaticProfileServiceHealthy(boolean staticProfileServiceHealthy) { 
            this.staticProfileServiceHealthy = staticProfileServiceHealthy; 
        }

        public boolean isAggregationServiceHealthy() { return aggregationServiceHealthy; }
        public void setAggregationServiceHealthy(boolean aggregationServiceHealthy) { 
            this.aggregationServiceHealthy = aggregationServiceHealthy; 
        }

        public List<String> getErrors() { return errors; }
        public void addError(String error) { errors.add(error); }
    }
}