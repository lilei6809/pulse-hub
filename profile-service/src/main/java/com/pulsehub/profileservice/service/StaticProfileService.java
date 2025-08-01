package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.StaticUserProfile;
import com.pulsehub.profileservice.repository.StaticUserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/**
 * 静态用户画像服务
 * 
 * 【设计目标】
 * - 管理静态用户画像数据的CRUD操作
 * - 提供智能缓存策略支持不同业务场景
 * - 确保数据一致性和乐观锁控制
 * - 支持用户画像的完整性验证和评分
 * 
 * 【与旧版区别】
 * - 使用StaticUserProfile替代简单的UserProfile
 * - 支持完整的用户信息字段（姓名、邮箱、电话、城市、年龄段等）
 * - 集成版本控制和乐观锁机制
 * - 提供业务友好的查询和验证方法
 * 
 * 【缓存策略】
 * - CRM场景：快速响应，不缓存空值，TTL=10分钟
 * - Analytics场景：长期缓存，允许空值，TTL=4小时
 * - 通用场景：平衡策略，TTL=1小时
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaticProfileService {

    // @Repository 也是  @Component 会自动注入到 spring 中, @RequiredArgsConstructor 扫描 这个 component, 创建 StaticProfileService
    private final StaticUserProfileRepository staticUserProfileRepository;

    // ===================================================================
    // 创建和更新操作
    // ===================================================================

    /**
     * 创建新的静态用户画像
     * 
     * @param staticProfile 静态用户画像数据
     * @return 保存后的用户画像（包含生成的版本号等）
     * @throws IllegalStateException 如果用户ID已存在
     */
    @CacheEvict(value = "crm-user-profiles", key = "#staticProfile.userId")
    public StaticUserProfile createProfile(StaticUserProfile staticProfile) {
        // 检查 userId 是否已存在
        if (staticUserProfileRepository.existsById(staticProfile.getUserId())) {
            throw new IllegalStateException("Static profile for user ID " + staticProfile.getUserId() + " already exists.");
        }

        // 设置注册时间（如果未设置）
        if (staticProfile.getRegistrationDate() == null) {
            staticProfile.setRegistrationDate(Instant.now());
        }

        // 保存到 数据库
        StaticUserProfile saved = staticUserProfileRepository.save(staticProfile);
        log.info("✅ 创建静态用户画像: {} (完整度: {}%)", 
                saved.getUserId(), saved.getProfileCompletenessScore());
        
        return saved;
    }

    /**
     * 更新现有静态用户画像
     * 使用乐观锁防止并发更新冲突
     * 
     * @param staticProfile 要更新的用户画像
     * @return 更新后的用户画像
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException 版本冲突
     */
    @CachePut(value = "crm-user-profiles", key = "#staticProfile.userId")
    public StaticUserProfile updateProfile(StaticUserProfile staticProfile) {
        // JPA的@Version注解会自动处理乐观锁
        StaticUserProfile updated = staticUserProfileRepository.save(staticProfile);
        log.info("🔄 更新静态用户画像: {} (版本: {}, 完整度: {}%)", 
                updated.getUserId(), updated.getVersion(), updated.getProfileCompletenessScore());
        
        return updated;
    }

    /**
     * 部分更新用户画像字段
     * 只更新非空字段，保持其他字段不变
     * 
     * @param userId 用户ID
     * @param updates 要更新的字段（只会更新非空字段）
     * @return 更新后的用户画像
     */
    @CachePut(value = "crm-user-profiles", key = "#userId")
    public Optional<StaticUserProfile> partialUpdate(String userId, StaticUserProfile updates) {
        Optional<StaticUserProfile> profile = staticUserProfileRepository.findById(userId);

        return profile
                .map(existing -> {
                    // existing 就是 Optional 包装下的 StaticUserProfile 对象
                    // 如果 profile 是 empty(), 就直接返回这个空的 profile

                    // 只更新非空字段
                    if (updates.getRealName() != null) existing.setRealName(updates.getRealName());
                    if (updates.getEmail() != null) existing.setEmail(updates.getEmail());
                    if (updates.getPhoneNumber() != null) existing.setPhoneNumber(updates.getPhoneNumber());
                    if (updates.getCity() != null) existing.setCity(updates.getCity());
                    if (updates.getGender() != null) existing.setGender(updates.getGender());
                    if (updates.getAgeGroup() != null) existing.setAgeGroup(updates.getAgeGroup());
                    if (updates.getSourceChannel() != null) existing.setSourceChannel(updates.getSourceChannel());

                    StaticUserProfile updated = staticUserProfileRepository.save(existing);
                    log.info("🔧 部分更新用户画像: {} (完整度: {}%)", 
                            userId, updated.getProfileCompletenessScore());
                    return updated;
                });
    }

    // ===================================================================
    // 查询操作 - 多场景缓存策略
    // ===================================================================

    /**
     * CRM场景：获取用户画像
     * 快速响应，实时性优先, 不缓存空值
     */
    @Cacheable(value = "crm-user-profiles", key = "#userId", unless = "#result.isEmpty()")
    public Optional<StaticUserProfile> getProfileForCRM(String userId) {
        log.debug("🎯 CRM查询静态用户画像: {}", userId);
        return staticUserProfileRepository.findById(userId);
    }

    /**
     * Analytics场景：获取用户画像
     * 允许缓存空值，长期缓存
     * 因为为什么这个 userId 的画像为空这个问题具备分析意义, 所以需要缓存空值
     */
    @Cacheable(value = "analytics-user-profiles", key = "#userId")
    public Optional<StaticUserProfile> getProfileForAnalytics(String userId) {
        log.debug("📊 Analytics查询静态用户画像: {}", userId);
        return staticUserProfileRepository.findById(userId);
    }

    /**
     * 通用场景：获取用户画像
     * 平衡的缓存策略
     */
    @Cacheable(value = "user-profiles", key = "#userId")
    public Optional<StaticUserProfile> getProfile(String userId) {
        log.debug("🔍 通用查询静态用户画像: {}", userId);
        return staticUserProfileRepository.findById(userId);
    }

    /**
     * 批量获取用户画像
     * 不使用缓存，直接查询数据库
     */
    public List<StaticUserProfile> getProfiles(List<String> userIds) {
        log.debug("📦 批量查询静态用户画像: {} 个用户", userIds.size());
        return staticUserProfileRepository.findAllById(userIds);
    }

    // ===================================================================
    // 业务查询方法
    // ===================================================================

    /**
     * 根据邮箱查找用户画像
     */
    public Optional<StaticUserProfile> getProfileByEmail(String email) {
        log.debug("📧 根据邮箱查询用户画像: {}", email);
        return staticUserProfileRepository.findByEmail(email);
    }

    /**
     * 根据手机号查找用户画像
     */
    public Optional<StaticUserProfile> getProfileByPhoneNumber(String phoneNumber) {
        log.debug("📱 根据手机号查询用户画像: {}", phoneNumber);
        return staticUserProfileRepository.findByPhoneNumber(phoneNumber);
    }

    /**
     * 查找新用户（注册时间在指定天数内）
     */
    public List<StaticUserProfile> getNewUsers(int days) {
        Instant cutoffDate = Instant.now().minusSeconds(days * 24 * 60 * 60L);
        log.debug("👶 查询新用户（{}天内注册）", days);
        return staticUserProfileRepository.findByRegistrationDateAfter(cutoffDate);
    }

    /**
     * 根据来源渠道查找用户
     */
    public List<StaticUserProfile> getUsersBySourceChannel(String sourceChannel) {
        log.debug("📣 根据来源渠道查询用户: {}", sourceChannel);
        return staticUserProfileRepository.findBySourceChannel(sourceChannel);
    }

    /**
     * 根据性别查找用户
     */
    public List<StaticUserProfile> getUsersByGender(StaticUserProfile.Gender gender) {
        log.debug("👥 根据性别查询用户: {}", gender);
        return staticUserProfileRepository.findByGenderAndIsDeletedFalse(gender);
    }

    /**
     * 根据城市查找用户
     */
    public List<StaticUserProfile> getUsersByCity(String city) {
        log.debug("🏙️ 根据城市查询用户: {}", city);
        return staticUserProfileRepository.findByCity(city);
    }

    /**
     * 查找信息完整的用户（完整度 >= 80%）
     */
    public List<StaticUserProfile> getCompleteProfiles() {
        log.debug("✅ 查询信息完整的用户画像");
        return staticUserProfileRepository.findCompleteProfiles();
    }

    // ===================================================================
    // 验证和检查方法
    // ===================================================================

    /**
     * 检查用户是否存在
     */
    @Cacheable(value = "user-existence-check", key = "#userId")
    public boolean profileExists(String userId) {
        return staticUserProfileRepository.existsById(userId);
    }

    /**
     * 检查邮箱是否已被使用
     */
    public boolean emailExists(String email) {
        return staticUserProfileRepository.existsByEmail(email);
    }

    /**
     * 检查手机号是否已被使用
     */
    public boolean phoneNumberExists(String phoneNumber) {
        return staticUserProfileRepository.existsByPhoneNumber(phoneNumber);
    }

    /**
     * 验证用户画像数据
     * 
     * @param staticProfile 要验证的用户画像
     * @return 验证结果和建议
     */
    public ProfileValidationResult validateProfile(StaticUserProfile staticProfile) {
        ProfileValidationResult result = new ProfileValidationResult();
        
        // 检查必填字段
        if (staticProfile.getUserId() == null || staticProfile.getUserId().trim().isEmpty()) {
            result.addError("用户ID不能为空");
        }
        
        if (staticProfile.getRegistrationDate() == null) {
            result.addWarning("建议设置注册时间");
        }

        // 检查邮箱格式
        if (staticProfile.getEmail() != null && !staticProfile.hasValidEmail()) {
            result.addError("邮箱格式不正确");
        }

        // 检查手机号格式
        if (staticProfile.getPhoneNumber() != null && !staticProfile.hasValidPhoneNumber()) {
            result.addError("手机号格式不正确");
        }

        // 检查重复
        if (staticProfile.getEmail() != null && emailExists(staticProfile.getEmail())) {
            result.addError("邮箱已被其他用户使用");
        }

        if (staticProfile.getPhoneNumber() != null && phoneNumberExists(staticProfile.getPhoneNumber())) {
            result.addError("手机号已被其他用户使用");
        }

        // 完整度建议
        int completeness = staticProfile.getProfileCompletenessScore();
        if (completeness < 50) {
            result.addWarning("用户信息完整度较低(" + completeness + "%)，建议补充更多信息");
        }

        return result;
    }

    // ===================================================================
    // 软删除管理方法
    // ===================================================================

    /**
     * 软删除用户画像
     * 标记为已删除但保留数据，清除相关缓存
     * 
     * @param userId 用户ID
     * @return 是否成功删除
     */
    @CacheEvict(value = {"crm-user-profiles", "analytics-user-profiles", "user-profiles", "user-existence-check"}, 
                key = "#userId")
    public boolean softDeleteProfile(String userId) {
        return staticUserProfileRepository.findById(userId)
                .map(profile -> {
                    profile.softDelete();
                    staticUserProfileRepository.save(profile);
                    log.info("🗑️ 软删除用户画像: {}", userId);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 恢复已软删除的用户画像
     * 将删除标记置为false，清除相关缓存
     * 
     * @param userId 用户ID
     * @return 是否成功恢复
     */
    @CacheEvict(value = {"crm-user-profiles", "analytics-user-profiles", "user-profiles", "user-existence-check"}, 
                key = "#userId")
    public boolean restoreProfile(String userId) {
        return staticUserProfileRepository.findById(userId)
                .map(profile -> {
                    profile.restore();
                    staticUserProfileRepository.save(profile);
                    log.info("🔄 恢复用户画像: {}", userId);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 永久删除用户画像（物理删除）
     * 谨慎使用，删除后无法恢复
     * 
     * @param userId 用户ID
     * @return 是否成功删除
     */
    @CacheEvict(value = {"crm-user-profiles", "analytics-user-profiles", "user-profiles", "user-existence-check"}, 
                key = "#userId")
    public boolean hardDeleteProfile(String userId) {
        if (staticUserProfileRepository.existsById(userId)) {
            staticUserProfileRepository.deleteById(userId);
            log.warn("⚠️ 永久删除用户画像: {}", userId);
            return true;
        }
        return false;
    }

    /**
     * 查找已软删除的用户画像
     * 
     * @return 已删除的用户列表
     */
    public List<StaticUserProfile> getDeletedProfiles() {
        log.debug("🗑️ 查询已软删除的用户画像");
        return staticUserProfileRepository.findByIsDeletedTrue();
    }

    /**
     * 批量恢复用户画像
     * 
     * @param userIds 用户ID列表
     * @return 成功恢复的用户数量
     */
    public int batchRestoreProfiles(List<String> userIds) {
        int restoredCount = 0;
        for (String userId : userIds) {
            if (restoreProfile(userId)) {
                restoredCount++;
            }
        }
        log.info("🔄 批量恢复用户画像: {}/{} 成功", restoredCount, userIds.size());
        return restoredCount;
    }

    // ===================================================================
    // 缓存管理方法
    // ===================================================================

    /**
     * 清除用户的所有缓存
     */
    @CacheEvict(value = {"crm-user-profiles", "analytics-user-profiles", "user-profiles", "user-existence-check"}, 
                key = "#userId")
    public void evictUserCaches(String userId) {
        log.info("🗑️ 清除用户缓存: {}", userId);
    }

    /**
     * 清除所有用户画像缓存
     */
    @CacheEvict(value = {"crm-user-profiles", "analytics-user-profiles", "user-profiles", "user-existence-check"}, 
                allEntries = true)
    public void evictAllCaches() {
        log.info("🗑️ 清除所有用户画像缓存");
    }

    // ===================================================================
    // 统计和报告方法
    // ===================================================================

    /**
     * 获取用户画像统计信息
     */
    public ProfileStatistics getProfileStatistics() {
        long totalProfiles = staticUserProfileRepository.count();
        long newUsersThisWeek = getNewUsers(7).size();
        long completeProfiles = getCompleteProfiles().size();
        
        ProfileStatistics stats = new ProfileStatistics();
        stats.setTotalProfiles(totalProfiles);
        stats.setNewUsersThisWeek(newUsersThisWeek);
        stats.setCompleteProfiles(completeProfiles);
        stats.setCompletenessRate(totalProfiles > 0 ? (double) completeProfiles / totalProfiles * 100 : 0);
        
        log.info("📊 用户画像统计 - 总数: {}, 本周新增: {}, 完整画像: {}, 完整率: {:.1f}%", 
                totalProfiles, newUsersThisWeek, completeProfiles, stats.getCompletenessRate());
        
        return stats;
    }

    // ===================================================================
    // 内部类：验证结果
    // ===================================================================

    public static class ProfileValidationResult {
        private final List<String> errors = new java.util.ArrayList<>();
        private final List<String> warnings = new java.util.ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean isValid() {
            return !hasErrors();
        }
    }

    // ===================================================================
    // 内部类：统计信息
    // ===================================================================

    public static class ProfileStatistics {
        private long totalProfiles;
        private long newUsersThisWeek;
        private long completeProfiles;
        private double completenessRate;

        // Getters and Setters
        public long getTotalProfiles() { return totalProfiles; }
        public void setTotalProfiles(long totalProfiles) { this.totalProfiles = totalProfiles; }

        public long getNewUsersThisWeek() { return newUsersThisWeek; }
        public void setNewUsersThisWeek(long newUsersThisWeek) { this.newUsersThisWeek = newUsersThisWeek; }

        public long getCompleteProfiles() { return completeProfiles; }
        public void setCompleteProfiles(long completeProfiles) { this.completeProfiles = completeProfiles; }

        public double getCompletenessRate() { return completenessRate; }
        public void setCompletenessRate(double completenessRate) { this.completenessRate = completenessRate; }
    }
}