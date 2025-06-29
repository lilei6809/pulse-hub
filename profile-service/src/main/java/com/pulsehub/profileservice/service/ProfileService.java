package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 用户画像服务类 - 提供多种缓存策略
 * 
 * 本服务演示了不同的缓存配置策略：
 * 1. 默认策略：缓存所有结果，包括空值
 * 2. 选择性策略：只缓存存在的用户数据
 * 3. 更新策略：数据更新时自动管理缓存
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
    @Cacheable(value = "user-profiles", key = "#userId", unless = "#result.isEmpty()")
    public Optional<UserProfile> getProfileByUserId(String userId) {
        log.info("从数据库查询用户画像（不缓存空值）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 🎯 CRM/CDP 专用：销售和营销场景的用户画像查询
     * 
     * 【业务背景】
     * - 销售人员需要立即看到新录入的客户信息
     * - 营销系统需要实时响应用户行为变化
     * - 客服需要最新的客户状态进行支持
     * 
     * 【缓存策略】
     * - 不缓存空值：确保新用户立即可见
     * - 短TTL：平衡性能和数据新鲜度
     * - 条件缓存：只为有效用户启用缓存
     * 
     * 【成本效益分析】
     * - 优势：提升销售转化率、营销精准度、客服质量
     * - 成本：增加数据库查询，但在CRM场景下是值得的
     * 
     * @param userId 要查询的用户ID
     * @return 包含用户画像的Optional，针对CRM业务优化
     */
    @Cacheable(value = "crm-user-profiles", key = "#userId", 
               unless = "#result.isEmpty()",
               condition = "#userId != null && #userId.length() > 0")
    public Optional<UserProfile> getProfileForCRMOperations(String userId) {
        log.info("CRM场景查询用户画像（实时性优先）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 🎯 CRM/CDP 专用：数据分析和报表场景的用户画像查询
     * 
     * 【业务背景】
     * - 数据分析师生成定期报表
     * - 历史数据分析，对实时性要求不高
     * - 批量数据处理，性能和稳定性更重要
     * 
     * 【缓存策略】
     * - 缓存空值：防止重复查询不存在的历史用户
     * - 长TTL：减少数据库压力
     * - 全量缓存：包括空值，防止分析任务被无效查询影响
     * 
     * 【适用场景】
     * - 历史数据分析
     * - 定期报表生成
     * - 批量数据处理任务
     * 
     * @param userId 要查询的用户ID
     * @return 包含用户画像的Optional，针对分析场景优化
     */
    @Cacheable(value = "analytics-user-profiles", key = "#userId")
    public Optional<UserProfile> getProfileForAnalytics(String userId) {
        log.info("数据分析场景查询用户画像（稳定性优先）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 传统缓存策略：缓存所有结果（包括空值）
     * 
     * 【适用场景】
     * - 面临缓存穿透攻击风险
     * - 查询模式相对稳定
     * - 新用户注册不频繁
     * 
     * 【特点】
     * - 防止缓存穿透攻击
     * - 减少重复的无效数据库查询
     * - 占用更多缓存内存
     * - 新用户注册后可能需要手动清除缓存
     * 
     * 【注意】
     * 如果需要此策略，请启用此方法并禁用上面的方法
     */
    // @Cacheable(value = "user-profiles", key = "#userId")
    public Optional<UserProfile> getProfileByUserIdWithNullCache(String userId) {
        log.info("从数据库查询用户画像（缓存所有结果）: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 条件缓存策略：基于复杂条件决定是否缓存
     * 
     * 【适用场景】
     * - 需要根据用户类型或其他属性决定缓存策略
     * - 某些特殊用户的数据不应该被长期缓存
     * 
     * 【示例条件】
     * - unless = "#result.isEmpty()": 不缓存空值
     * - unless = "#result.isPresent() && #result.get().isVip()": 不缓存VIP用户
     * - condition = "#userId.length() > 3": 只为长ID用户启用缓存
     */
    // @Cacheable(value = "user-profiles", key = "#userId", 
    //           unless = "#result.isEmpty()", 
    //           condition = "#userId != null && #userId.length() > 3")
    public Optional<UserProfile> getProfileWithComplexCaching(String userId) {
        log.info("使用复杂缓存策略查询用户: {}", userId);
        return userProfileRepository.findById(userId);
    }

    /**
     * 更新用户画像并自动清除缓存
     * 
     * 【缓存管理】
     * 使用 @CacheEvict 确保数据更新后缓存失效
     * 
     * 【适用场景】
     * - 用户资料更新
     * - 需要确保缓存与数据库数据一致性
     */
    @CacheEvict(value = {"user-profiles", "crm-user-profiles", "analytics-user-profiles"}, key = "#userId")
    public UserProfile updateProfile(String userId, UserProfile updatedProfile) {
        log.info("更新用户画像并清除所有相关缓存: {}", userId);
        updatedProfile.setUserId(userId);
        return userProfileRepository.save(updatedProfile);
    }

    /**
     * 更新用户画像并重新缓存
     * 
     * 【缓存管理】
     * 使用 @CachePut 确保缓存立即更新为最新数据
     * 
     * 【特点】
     * - 数据库更新和缓存更新在同一操作中完成
     * - 避免了缓存失效后的第一次查询延迟
     */
    @CachePut(value = "user-profiles", key = "#userId")
    public UserProfile updateAndCacheProfile(String userId, UserProfile updatedProfile) {
        log.info("更新用户画像并重新缓存: {}", userId);
        updatedProfile.setUserId(userId);
        return userProfileRepository.save(updatedProfile);
        }

    /**
     * 批量清除所有用户画像缓存
     * 
     * 【使用场景】
     * - 系统维护
     * - 数据迁移后的缓存刷新
     * - 缓存空间清理
     */
    @CacheEvict(value = {"user-profiles", "crm-user-profiles", "analytics-user-profiles"}, allEntries = true)
    public void clearAllProfileCache() {
        log.info("清除所有用户画像缓存");
    }

    public boolean profileExists(String userId) {
        return userProfileRepository.findById(userId).isPresent();
    }
} 