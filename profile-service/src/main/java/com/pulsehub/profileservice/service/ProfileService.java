package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.List;

/**
 * 🎯 Profile Service - 缓存配置选择机制演示
 * 
 * 本类展示了如何在业务代码中正确使用不同的缓存配置。
 * Spring Cache通过@Cacheable注解的value参数来匹配CacheConfig中的配置。
 * 
 * 【配置匹配机制】
 * 1. Spring启动时加载CacheConfig，注册所有缓存配置到CacheManager
 * 2. 运行时遇到@Cacheable注解，提取value参数作为cacheName
 * 3. CacheManager根据cacheName查找对应配置：
 *    - 找到匹配配置 → 使用专用配置（TTL、空值策略等）
 *    - 未找到匹配 → 使用默认配置（15分钟TTL，不缓存空值）
 * 
 * 【配置注册表映射】
 * ┌─────────────────────────┬──────────────────┬─────────────────────────┐
 * │      @Cacheable值       │     配置来源     │        配置特点         │
 * ├─────────────────────────┼──────────────────┼─────────────────────────┤
 * │ "crm-user-profiles"     │ 专用配置         │ 10分钟TTL, 不缓存空值   │
 * │ "analytics-user-profiles"│ 专用配置        │ 4小时TTL, 缓存空值      │
 * │ "user-behaviors"        │ 专用配置         │ 30分钟TTL, 不缓存空值   │
 * │ "system-configs"        │ 专用配置         │ 24小时TTL, 缓存所有值   │
 * │ "any-other-name"        │ 默认配置         │ 15分钟TTL, 不缓存空值   │
 * └─────────────────────────┴──────────────────┴─────────────────────────┘
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileService {

    private final UserProfileRepository userProfileRepository;

    /**
     * Creates a new user profile if one does not already exist for the given userId.
     *
     * @param userId The ID of the user to create a profile for.
     * @return The newly created UserProfile.
     * @throws IllegalStateException if a profile for the given userId already exists.
     */
    public UserProfile createProfile(String userId) {
        // First, check if a profile for this user already exists to prevent duplicates.
        Optional<UserProfile> existingProfile = userProfileRepository.findById(userId);
        if (existingProfile.isPresent()) {
            // In a real-world scenario, how to handle this could be a business decision.
            // For now, we'll throw an exception to make the behavior explicit.
            throw new IllegalStateException("Profile for user ID " + userId + " already exists.");
        }

        UserProfile newProfile = new UserProfile();
        newProfile.setUserId(userId);
        return userProfileRepository.save(newProfile);
    }

    /**
     * 创建新用户画像（基于UserProfile对象）
     * 
     * 【缓存策略】
     * 新建用户后，如果之前查询过该ID且缓存了空值，需要清除缓存
     * 使用 @CacheEvict 确保清除可能存在的空值缓存
     */
    @CacheEvict(value = "user-profiles", key = "#userProfile.userId")
    public UserProfile createProfile(UserProfile userProfile) {
        log.info("创建新用户画像并清除可能的空值缓存: {}", userProfile.getUserId());
        return userProfileRepository.save(userProfile);
    }

    /**
     * 根据用户ID获取用户画像（不缓存空值版本）
     * 
     * 【缓存策略】
     * - 使用 unless = "#result.isEmpty()" 确保空的 Optional 不被缓存
     * - 只缓存实际存在的用户数据
     * - 新用户注册后能被立即发现
     * 
     * 【适用场景】
     * - 用户注册频繁的系统
     * - 需要立即发现新用户的业务场景
     * - 内存资源宝贵，不希望缓存无效查询
     * 
     * 【注意】
     * - 重复查询不存在的用户会每次访问数据库
     * - 在高并发下可能面临缓存穿透风险
     *
     * @param userId 要查询的用户ID
     * @return 包含用户画像的Optional，如果不存在则为空
     */
    @Cacheable(value = "user-profiles", key = "#userId", unless = "#result == null")
    public Optional<UserProfile> getProfileByUserId(String userId) {
        log.info("从数据库查询用户画像（不缓存空值）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 🎯 CRM场景专用方法
     * 
     * 【配置匹配说明】
     * value = "crm-user-profiles" 
     * ↓ 匹配过程
     * CacheManager.getCache("crm-user-profiles")
     * ↓ 查找配置注册表
     * 找到：builder.withCacheConfiguration("crm-user-profiles", ...)
     * ↓ 应用配置
     * TTL=10分钟, disableCachingNullValues(), prefix="pulsehub:crm:"
     * 
     * 【业务价值】
     * - 销售人员查询客户信息
     * - 客服处理客户咨询
     * - 营销活动实时投放
     * 
     * 【配置效果】
     * - 新用户注册后立即可见（不缓存空值）
     * - 数据保持10分钟新鲜度
     * - Redis Key: pulsehub:crm:crm-user-profiles::user123
     */
    @Cacheable(value = "crm-user-profiles", key = "#userId", unless = "#result == null")
    public Optional<UserProfile> getProfileForCRM(String userId) {
        log.info("CRM场景查询用户画像（实时性优先）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 📊 Analytics场景专用方法
     * 
     * 【配置匹配说明】
     * value = "analytics-user-profiles"
     * ↓ 匹配过程
     * CacheManager.getCache("analytics-user-profiles") 
     * ↓ 查找配置注册表
     * 找到：builder.withCacheConfiguration("analytics-user-profiles", ...)
     * ↓ 应用配置
     * TTL=4小时, 允许缓存空值, prefix="pulsehub:analytics:"
     * 
     * 【业务价值】
     * - BI报表生成
     * - 数据分析查询
     * - 管理驾驶舱展示
     * 
     * 【配置效果】
     * - 防止分析任务缓存穿透（缓存空值）
     * - 长期缓存减少DB压力
     * - Redis Key: pulsehub:analytics:analytics-user-profiles::user123
     */
    @Cacheable(value = "analytics-user-profiles", key = "#userId")
    public Optional<UserProfile> getProfileForAnalytics(String userId) {
        log.info("数据分析场景查询用户画像（稳定性优先）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 🔍 用户行为场景专用方法
     * 
     * 【配置匹配说明】
     * value = "user-behaviors"
     * ↓ 匹配过程  
     * CacheManager.getCache("user-behaviors")
     * ↓ 查找配置注册表
     * 找到：builder.withCacheConfiguration("user-behaviors", ...)
     * ↓ 应用配置
     * TTL=30分钟, disableCachingNullValues(), prefix="pulsehub:behavior:"
     * 
     * 【业务价值】
     * - 实时推荐系统
     * - 用户行为分析
     * - A/B测试数据
     * 
     * 【配置效果】
     * - 中等TTL平衡实时性和性能
     * - 新行为数据立即可见
     * - Redis Key: pulsehub:behavior:user-behaviors::user123
     */
    @Cacheable(value = "user-behaviors", key = "#userId", unless = "#result.isEmpty()")
    public List<String> getUserBehaviors(String userId) {
        log.info("行为查询 - 使用user-behaviors配置，TTL=30分钟");
        // 模拟返回用户行为数据
        return List.of("login", "view_product", "add_to_cart");
    }

    /**
     * ⚙️ 系统配置场景专用方法
     * 
     * 【配置匹配说明】
     * value = "system-configs"
     * ↓ 匹配过程
     * CacheManager.getCache("system-configs")
     * ↓ 查找配置注册表
     * 找到：builder.withCacheConfiguration("system-configs", ...)
     * ↓ 应用配置
     * TTL=24小时, 缓存所有值包括null, prefix="pulsehub:config:"
     * 
     * 【业务价值】
     * - 系统参数配置
     * - 元数据字典
     * - 功能开关管理
     * 
     * 【配置效果】
     * - 超长TTL适合低频变化的配置
     * - 缓存null值减少无效查询
     * - Redis Key: pulsehub:config:system-configs::feature.enable.recommendation
     */
    @Cacheable(value = "system-configs", key = "#configKey")
    public String getSystemConfig(String configKey) {
        log.info("配置查询 - 使用system-configs配置，TTL=24小时");
        // 模拟系统配置查询
        return "config-value-for-" + configKey;
    }

    /**
     * 🔄 兼容性方法（使用原有配置）
     * 
     * 【配置匹配说明】
     * value = "user-profiles"
     * ↓ 匹配过程
     * CacheManager.getCache("user-profiles")
     * ↓ 查找配置注册表
     * 找到：builder.withCacheConfiguration("user-profiles", ...)
     * ↓ 应用配置
     * TTL=1小时, 使用默认空值策略
     * 
     * 【使用建议】
     * 保留用于向后兼容，新功能建议使用上面的专用方法
     */
    @Cacheable(value = "user-profiles", key = "#userId")
    public Optional<UserProfile> getProfile(String userId) {
        log.info("兼容查询 - 使用user-profiles配置，TTL=1小时");
        return userProfileRepository.findById(userId);
    }

    /**
     * ❓ 演示默认配置的使用
     * 
     * 【配置匹配说明】
     * value = "unknown-cache"
     * ↓ 匹配过程
     * CacheManager.getCache("unknown-cache")
     * ↓ 查找配置注册表
     * 未找到匹配的专用配置
     * ↓ 使用默认配置
     * 使用 cacheConfiguration() 的配置：TTL=15分钟, 不缓存空值
     * 
     * 【实际效果】
     * - 自动创建名为"unknown-cache"的缓存
     * - 应用默认的15分钟TTL和不缓存空值策略
     * - Redis Key: unknown-cache::user123 (无特殊前缀)
     * 
     * 【使用场景】
     * - 临时缓存需求
     * - 测试和开发环境
     * - 尚未分类的业务数据
     */
    @Cacheable(value = "unknown-cache", key = "#userId")
    public String getTemporaryData(String userId) {
        log.info("临时查询 - 使用默认配置，TTL=15分钟");
        return "temporary-data-for-" + userId;
    }

    /**
     * 🔧 缓存管理方法示例
     * 
     * 演示如何手动操作不同的缓存层
     */
    
    @CacheEvict(value = "crm-user-profiles", key = "#userId")
    public void evictCRMCache(String userId) {
        log.info("清除CRM缓存: " + userId);
    }

    @CacheEvict(value = {"crm-user-profiles", "analytics-user-profiles", "user-behaviors"}, key = "#userId")
    public void evictAllUserCaches(String userId) {
        log.info("清除用户所有缓存: " + userId);
    }

    @CachePut(value = "crm-user-profiles", key = "#userProfile.userId")
    public UserProfile updateProfile(UserProfile userProfile) {
        log.info("更新并刷新CRM缓存: " + userProfile.getUserId());
        return userProfileRepository.save(userProfile);
    }

    /**
     * 📋 配置验证方法
     * 
     * 用于验证不同缓存配置是否按预期工作
     */
    public void demonstrateCacheSelection() {
        log.info("\n🎯 ===== 缓存配置选择演示 =====");
        
        // 测试不同配置的选择
        getProfileForCRM("demo-user");          // 使用crm-user-profiles配置
        getProfileForAnalytics("demo-user");    // 使用analytics-user-profiles配置  
        getUserBehaviors("demo-user");          // 使用user-behaviors配置
        getSystemConfig("demo.feature.flag");  // 使用system-configs配置
        getProfile("demo-user");               // 使用user-profiles配置
        getTemporaryData("demo-user");         // 使用默认配置
        
        log.info("🎯 ===== 演示完成 =====\n");
    }

    public boolean profileExists(String userId) {
        return userProfileRepository.findById(userId).isPresent();
    }
} 