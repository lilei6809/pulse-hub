package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 🔧 手动缓存实现示例
 * 
 * 这个类展示了如何**不使用任何注解**，手动实现缓存逻辑。
 * 通过对比这个手动版本和注解版本，你可以理解：
 * 1. @Cacheable注解背后到底发生了什么
 * 2. Spring Cache如何简化缓存代码
 * 3. 缓存的具体执行流程
 * 
 * 【学习价值】
 * - 理解缓存的本质机制
 * - 掌握RedisTemplate的直接使用
 * - 了解注解背后的"魔法"
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualCacheExample {

    private final UserProfileRepository userProfileRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 🎯 手动实现的缓存查询方法
     * 
     * 这个方法**完全手动**实现了缓存逻辑，相当于：
     * @Cacheable(value = "user-profiles", key = "#userId")
     * 
     * 【执行步骤】
     * 1. 构造Redis Key
     * 2. 尝试从Redis获取数据
     * 3. 如果Redis有数据，直接返回（缓存命中）
     * 4. 如果Redis没有数据，查询数据库（缓存未命中）
     * 5. 将数据库结果存入Redis
     * 6. 返回结果
     */
    public Optional<UserProfile> getProfileManual(String userId) {
        log.info("🔧 开始手动缓存查询用户: {}", userId);
        
        // ===== 步骤1: 构造Redis Key =====
        String redisKey = "user-profiles::" + userId;
        log.info("   1️⃣ 构造Redis Key: {}", redisKey);
        
        // ===== 步骤2: 尝试从Redis获取数据 =====
        log.info("   2️⃣ 尝试从Redis获取数据...");
        Object cachedValue = redisTemplate.opsForValue().get(redisKey);
        
        if (cachedValue != null) {
            // ===== 缓存命中 =====
            log.info("   ✅ 缓存命中！从Redis获取数据");
            log.info("   📦 缓存数据类型: {}", cachedValue.getClass().getSimpleName());
            
            if (cachedValue instanceof UserProfile) {
                UserProfile profile = (UserProfile) cachedValue;
                log.info("   🎯 返回缓存的用户数据: {}", profile.getUserId());
                return Optional.of(profile);
            } else {
                log.warn("   ⚠️ 缓存数据类型异常，改为查询数据库");
                // 缓存数据格式异常，删除并重新查询
                redisTemplate.delete(redisKey);
            }
        } else {
            log.info("   ❌ 缓存未命中，需要查询数据库");
        }
        
        // ===== 步骤3: 查询数据库 =====
        log.info("   3️⃣ 查询数据库...");
        Optional<UserProfile> profileFromDb = userProfileRepository.findById(userId);
        
        if (profileFromDb.isPresent()) {
            // ===== 步骤4: 将数据存入Redis =====
            UserProfile profile = profileFromDb.get();
            log.info("   4️⃣ 数据库查询成功，准备缓存数据");
            log.info("   💾 存储到Redis: key={}, value={}", redisKey, profile.getUserId());
            
            // 存储到Redis，设置1小时过期时间
            redisTemplate.opsForValue().set(redisKey, profile, Duration.ofHours(1));
            log.info("   ✅ 数据已缓存，TTL=1小时");
            
        } else {
            log.info("   ❌ 数据库中未找到用户: {}", userId);
        }
        
        // ===== 步骤5: 返回结果 =====
        log.info("   5️⃣ 返回查询结果");
        return profileFromDb;
    }

    /**
     * 🎯 手动实现的CRM缓存查询（短TTL版本）
     * 
     * 相当于：
     * @Cacheable(value = "crm-user-profiles", key = "#userId", unless = "#result.isEmpty()")
     * 
     * 【区别】
     * - 使用不同的Redis Key前缀
     * - 使用更短的TTL (10分钟)
     * - 不缓存空结果
     */
    public Optional<UserProfile> getProfileForCRMManual(String userId) {
        log.info("🏃‍♂️ 开始CRM手动缓存查询用户: {}", userId);
        
        // CRM场景使用专用的Key前缀
        String redisKey = "pulsehub:crm:crm-user-profiles::" + userId;
        log.info("   🔑 CRM Redis Key: {}", redisKey);
        
        // 尝试从Redis获取
        Object cachedValue = redisTemplate.opsForValue().get(redisKey);
        
        if (cachedValue != null) {
            log.info("   ✅ CRM缓存命中");
            if (cachedValue instanceof UserProfile) {
                return Optional.of((UserProfile) cachedValue);
            }
        }
        
        log.info("   🔍 CRM缓存未命中，查询数据库");
        Optional<UserProfile> profileFromDb = userProfileRepository.findById(userId);
        
        // 只有当数据存在时才缓存（不缓存空结果）
        if (profileFromDb.isPresent()) {
            UserProfile profile = profileFromDb.get();
            log.info("   💾 CRM数据缓存: TTL=10分钟");
            // CRM场景使用10分钟TTL
            redisTemplate.opsForValue().set(redisKey, profile, Duration.ofMinutes(10));
        } else {
            log.info("   ⚠️ CRM不缓存空结果，新用户能立即被发现");
        }
        
        return profileFromDb;
    }

    /**
     * 🎯 手动实现缓存更新
     * 
     * 相当于：
     * @CachePut(value = "user-profiles", key = "#userProfile.userId")
     * 
     * 【执行步骤】
     * 1. 更新数据库
     * 2. 更新Redis缓存
     * 3. 返回结果
     */
    public UserProfile updateProfileManual(UserProfile userProfile) {
        log.info("🔄 开始手动更新用户画像: {}", userProfile.getUserId());
        
        // 步骤1: 更新数据库
        log.info("   1️⃣ 更新数据库...");
        UserProfile savedProfile = userProfileRepository.save(userProfile);
        
        // 步骤2: 更新缓存
        String redisKey = "user-profiles::" + userProfile.getUserId();
        log.info("   2️⃣ 更新Redis缓存: {}", redisKey);
        redisTemplate.opsForValue().set(redisKey, savedProfile, Duration.ofHours(1));
        
        log.info("   ✅ 数据库和缓存都已更新");
        return savedProfile;
    }

    /**
     * 🎯 手动实现缓存删除
     * 
     * 相当于：
     * @CacheEvict(value = "user-profiles", key = "#userId")
     * 
     * 【执行步骤】
     * 1. 从Redis删除指定Key
     * 2. 记录删除结果
     */
    public void evictProfileCacheManual(String userId) {
        log.info("🗑️ 开始手动删除缓存: {}", userId);
        
        String redisKey = "user-profiles::" + userId;
        log.info("   🔑 删除Redis Key: {}", redisKey);
        
        Boolean deleted = redisTemplate.delete(redisKey);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("   ✅ 缓存删除成功");
        } else {
            log.info("   ℹ️ 缓存不存在或删除失败");
        }
    }

    /**
     * 🎯 手动实现多级缓存清除
     * 
     * 相当于：
     * @CacheEvict(value = {"user-profiles", "crm-user-profiles"}, key = "#userId")
     * 
     * 【执行步骤】
     * 1. 删除所有相关的缓存Key
     * 2. 统计删除结果
     */
    public void evictAllUserCachesManual(String userId) {
        log.info("🧹 开始清除用户所有缓存: {}", userId);
        
        String[] keys = {
            "user-profiles::" + userId,
            "pulsehub:crm:crm-user-profiles::" + userId,
            "pulsehub:analytics:analytics-user-profiles::" + userId,
            "pulsehub:behavior:user-behaviors::" + userId
        };
        
        int deletedCount = 0;
        for (String key : keys) {
            log.info("   🗑️ 删除: {}", key);
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                deletedCount++;
            }
        }
        
        log.info("   ✅ 成功删除 {}/{} 个缓存", deletedCount, keys.length);
    }

    /**
     * 🔍 检查缓存状态的工具方法
     * 
     * 【功能】
     * 1. 检查指定用户的缓存是否存在
     * 2. 显示缓存的TTL（剩余过期时间）
     * 3. 显示缓存的数据内容
     */
    public void inspectCacheStatus(String userId) {
        log.info("🔍 检查用户缓存状态: {}", userId);
        
        String[] keys = {
            "user-profiles::" + userId,
            "pulsehub:crm:crm-user-profiles::" + userId,
            "pulsehub:analytics:analytics-user-profiles::" + userId
        };
        
        for (String key : keys) {
            // 检查Key是否存在
            Boolean exists = redisTemplate.hasKey(key);
            
            if (Boolean.TRUE.equals(exists)) {
                // 获取TTL（剩余过期时间）
                Long ttl = redisTemplate.getExpire(key);
                
                // 获取数据
                Object value = redisTemplate.opsForValue().get(key);
                
                log.info("   ✅ {}", key);
                log.info("      TTL: {} 秒", ttl);
                log.info("      数据: {}", value instanceof UserProfile ? 
                    ((UserProfile) value).getUserId() : value);
            } else {
                log.info("   ❌ {} (不存在)", key);
            }
        }
    }

    /**
     * 🎯 对比演示：注解版本 vs 手动版本
     * 
     * 【目的】
     * 通过同时调用注解版本和手动版本，让你看到：
     * 1. 两种方式的效果完全相同
     * 2. 注解只是语法糖，底层都是相同的Redis操作
     * 3. 手动版本让你理解缓存的每一个步骤
     */
    public void demonstrateAnnotationVsManual(String userId) {
        log.info("\n🎭 ===== 注解版本 vs 手动版本对比演示 =====");
        
        // 清空所有缓存，确保公平对比
        evictAllUserCachesManual(userId);
        
        log.info("\n--- 第一轮：手动版本 ---");
        long startTime = System.currentTimeMillis();
        Optional<UserProfile> manualResult = getProfileManual(userId);
        long manualTime = System.currentTimeMillis() - startTime;
        log.info("手动版本耗时: {} ms", manualTime);
        
        log.info("\n--- 检查缓存状态 ---");
        inspectCacheStatus(userId);
        
        log.info("\n--- 第二轮：再次手动版本（应该命中缓存）---");
        startTime = System.currentTimeMillis();
        Optional<UserProfile> manualCachedResult = getProfileManual(userId);
        long manualCachedTime = System.currentTimeMillis() - startTime;
        log.info("手动版本（缓存命中）耗时: {} ms", manualCachedTime);
        
        log.info("\n📊 性能对比:");
        log.info("   第一次查询（数据库）: {} ms", manualTime);
        log.info("   第二次查询（缓存）: {} ms", manualCachedTime);
        log.info("   性能提升: {}x", (double) manualTime / manualCachedTime);
        
        log.info("\n🎭 ===== 对比演示完成 =====\n");
    }
} 