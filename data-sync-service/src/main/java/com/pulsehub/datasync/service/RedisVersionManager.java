package com.pulsehub.datasync.service;

import com.pulsehub.common.redis.RedisAtomicOperations;
import com.pulsehub.common.redis.RedisDistributedLock;
import com.pulsehub.common.redis.RedisProfileData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis版本管理服务
 * 
 * 核心职责：
 * 1. 统一管理用户profile数据的版本控制
 * 2. 提供分布式锁保护的原子更新操作
 * 3. 处理版本冲突和数据一致性问题
 * 4. 支持立即同步和批量同步的版本策略
 * 
 * 版本控制策略：
 * - 每次更新严格递增版本号
 * - 使用分布式锁保证更新原子性
 * - 支持乐观锁检查，防止并发冲突
 * - 版本冲突时提供重试和降级机制
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class RedisVersionManager {

    private final RedisDistributedLock distributedLock;
    private final RedisAtomicOperations atomicOperations;
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public RedisVersionManager(RedisDistributedLock distributedLock, 
                              RedisAtomicOperations atomicOperations,
                              RedisTemplate<String, Object> redisTemplate) {
        this.distributedLock = distributedLock;
        this.atomicOperations = atomicOperations;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取用户profile的Redis键名
     * 统一键名规范：profile:user:{userId}
     */
    public String getProfileKey(String userId) {
        return "profile:user:" + userId;
    }

    /**
     * 获取用户锁的键名
     * 统一锁键规范：lock:profile:{userId}
     */
    public String getLockKey(String userId) {
        return "lock:profile:" + userId;
    }

    /**
     * 安全更新用户profile数据
     * 使用分布式锁保护，确保版本号的原子性递增
     * 
     * @param userId 用户ID
     * @param updates 要更新的数据
     * @param source 更新来源标识
     * @param lockTimeout 锁超时时间(秒)
     * @return 更新结果
     */
    public ProfileUpdateResult safeUpdateProfile(String userId, Map<String, Object> updates, 
                                                String source, int lockTimeout) {
        if (userId == null || updates == null || updates.isEmpty()) {
            log.warn("更新参数无效: userId={}, updates={}", userId, updates);
            return ProfileUpdateResult.failed("参数无效");
        }

        String profileKey = getProfileKey(userId);
        String lockKey = getLockKey(userId);
        
        // 获取分布式锁
        RedisDistributedLock.LockInfo lockInfo = distributedLock.tryLock(lockKey, lockTimeout, TimeUnit.SECONDS);
        if (!lockInfo.isAcquired()) {
            log.warn("获取用户profile锁失败: userId={}, lockKey={}", userId, lockKey);
            return ProfileUpdateResult.lockFailed("无法获取用户锁");
        }

        try {
            // 获取当前profile数据
            RedisProfileData currentData = getCurrentProfileData(profileKey, userId);
            Long currentVersion = currentData.getVersion();
            
            log.debug("开始更新用户profile: userId={}, currentVersion={}, updateFields={}, source={}", 
                userId, currentVersion, updates.keySet(), source);

            // 执行版本化更新
            RedisProfileData.UpdateResult updateResult = currentData.updateIfVersionMatch(
                currentVersion, updates, source);
            
            if (!updateResult.isSuccess()) {
                if (updateResult.isConflict()) {
                    log.warn("用户profile版本冲突: userId={}, expectedVersion={}, currentVersion={}", 
                        userId, updateResult.getOldVersion(), updateResult.getNewVersion());
                    return ProfileUpdateResult.versionConflict("版本冲突", 
                        updateResult.getOldVersion(), updateResult.getNewVersion());
                } else {
                    log.error("用户profile更新失败: userId={}, message={}", userId, updateResult.getMessage());
                    return ProfileUpdateResult.failed("更新失败: " + updateResult.getMessage());
                }
            }

            // 保存更新后的数据到Redis
            redisTemplate.opsForValue().set(profileKey, currentData);
            
            log.info("用户profile更新成功: userId={}, version {} -> {}, source={}, updateFields={}", 
                userId, updateResult.getOldVersion(), updateResult.getNewVersion(), source, updates.keySet());
            
            return ProfileUpdateResult.success("更新成功", 
                updateResult.getOldVersion(), updateResult.getNewVersion(), currentData);

        } catch (Exception e) {
            log.error("用户profile更新异常: userId={}, source={}", userId, source, e);
            return ProfileUpdateResult.failed("更新异常: " + e.getMessage());
        } finally {
            // 释放分布式锁
            boolean unlocked = distributedLock.unlock(lockInfo);
            if (!unlocked) {
                log.warn("释放用户profile锁失败: userId={}, lockKey={}", userId, lockKey);
            }
        }
    }

    /**
     * 原子更新profile数据（无锁版本）
     * 使用Redis原子操作，适用于低并发场景
     * 
     * @param userId 用户ID
     * @param updates 要更新的数据
     * @param expectedVersion 期望的版本号
     * @param source 更新来源
     * @return 原子更新结果
     */
    public AtomicUpdateResult atomicUpdateProfile(String userId, Map<String, Object> updates, 
                                                 Long expectedVersion, String source) {
        if (userId == null || updates == null || expectedVersion == null) {
            return AtomicUpdateResult.failed("参数无效");
        }

        String profileKey = getProfileKey(userId);
        
        try {
            // 构建新的profile数据
            RedisProfileData newData = RedisProfileData.builder()
                .profileData(new HashMap<>(updates))
                .version(expectedVersion + 1)
                .lastUpdated(Instant.now())
                .metadata(Map.of("source", source, "operation", "atomic_update"))
                .build();

            // 执行原子更新
            RedisAtomicOperations.AtomicOperationResult result = 
                atomicOperations.atomicUpdateProfile(profileKey, newData, expectedVersion);
            
            if (result.isSuccess()) {
                log.debug("原子更新profile成功: userId={}, version {} -> {}", 
                    userId, result.getOldVersion(), result.getNewVersion());
                return AtomicUpdateResult.success("原子更新成功", 
                    result.getOldVersion(), result.getNewVersion());
            } else if (result.isConflict()) {
                log.warn("原子更新版本冲突: userId={}, expectedVersion={}, currentVersion={}", 
                    userId, result.getOldVersion(), result.getNewVersion());
                return AtomicUpdateResult.versionConflict("版本冲突", 
                    result.getOldVersion(), result.getNewVersion());
            } else {
                log.error("原子更新失败: userId={}, message={}", userId, result.getMessage());
                return AtomicUpdateResult.failed("原子更新失败: " + result.getMessage());
            }

        } catch (Exception e) {
            log.error("原子更新profile异常: userId={}, expectedVersion={}", userId, expectedVersion, e);
            return AtomicUpdateResult.failed("原子更新异常: " + e.getMessage());
        }
    }

    /**
     * 获取当前用户profile数据
     * 如果数据不存在，创建默认数据
     * 
     * @param profileKey Redis键名
     * @param userId 用户ID
     * @return 当前profile数据
     */
    public RedisProfileData getCurrentProfileData(String profileKey, String userId) {
        try {
            Object existing = redisTemplate.opsForValue().get(profileKey);
            if (existing instanceof RedisProfileData) {
                return (RedisProfileData) existing;
            } else if (existing != null) {
                log.warn("Redis中的profile数据类型不匹配: userId={}, type={}", 
                    userId, existing.getClass().getSimpleName());
            }
            
            // 数据不存在或类型不匹配，创建新的默认数据
            log.info("创建新的用户profile数据: userId={}", userId);
            return RedisProfileData.createEmpty(userId);
            
        } catch (Exception e) {
            log.error("获取用户profile数据异常: userId={}", userId, e);
            return RedisProfileData.createEmpty(userId);
        }
    }

    /**
     * 获取用户当前版本号
     * 
     * @param userId 用户ID
     * @return 当前版本号，不存在则返回0
     */
    public Long getCurrentVersion(String userId) {
        try {
            String profileKey = getProfileKey(userId);
            RedisProfileData data = getCurrentProfileData(profileKey, userId);
            return data.getVersion();
        } catch (Exception e) {
            log.error("获取用户版本号异常: userId={}", userId, e);
            return 0L;
        }
    }

    /**
     * 检查用户profile是否存在
     * 
     * @param userId 用户ID
     * @return 是否存在
     */
    public boolean profileExists(String userId) {
        try {
            String profileKey = getProfileKey(userId);
            return Boolean.TRUE.equals(redisTemplate.hasKey(profileKey));
        } catch (Exception e) {
            log.error("检查用户profile存在性异常: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 删除用户profile数据
     * 使用分布式锁保护删除操作
     * 
     * @param userId 用户ID
     * @param reason 删除原因
     * @return 删除结果
     */
    public boolean deleteProfile(String userId, String reason) {
        String profileKey = getProfileKey(userId);
        String lockKey = getLockKey(userId);
        
        RedisDistributedLock.LockInfo lockInfo = distributedLock.tryLock(lockKey, 10, TimeUnit.SECONDS);
        if (!lockInfo.isAcquired()) {
            log.warn("获取删除锁失败: userId={}", userId);
            return false;
        }

        try {
            Boolean deleted = redisTemplate.delete(profileKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("删除用户profile成功: userId={}, reason={}", userId, reason);
                return true;
            } else {
                log.warn("用户profile不存在或删除失败: userId={}", userId);
                return false;
            }
        } catch (Exception e) {
            log.error("删除用户profile异常: userId={}, reason={}", userId, reason, e);
            return false;
        } finally {
            distributedLock.unlock(lockInfo);
        }
    }

    /**
     * Profile更新结果封装类
     */
    public static class ProfileUpdateResult {
        private final boolean success;
        private final String message;
        private final String type;
        private final Long oldVersion;
        private final Long newVersion;
        private final RedisProfileData updatedData;

        private ProfileUpdateResult(boolean success, String message, String type, 
                                  Long oldVersion, Long newVersion, RedisProfileData updatedData) {
            this.success = success;
            this.message = message;
            this.type = type;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.updatedData = updatedData;
        }

        public static ProfileUpdateResult success(String message, Long oldVersion, Long newVersion, 
                                                RedisProfileData updatedData) {
            return new ProfileUpdateResult(true, message, "SUCCESS", oldVersion, newVersion, updatedData);
        }

        public static ProfileUpdateResult failed(String message) {
            return new ProfileUpdateResult(false, message, "FAILED", null, null, null);
        }

        public static ProfileUpdateResult lockFailed(String message) {
            return new ProfileUpdateResult(false, message, "LOCK_FAILED", null, null, null);
        }

        public static ProfileUpdateResult versionConflict(String message, Long expectedVersion, Long currentVersion) {
            return new ProfileUpdateResult(false, message, "VERSION_CONFLICT", expectedVersion, currentVersion, null);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getType() { return type; }
        public Long getOldVersion() { return oldVersion; }
        public Long getNewVersion() { return newVersion; }
        public RedisProfileData getUpdatedData() { return updatedData; }
        public boolean isVersionConflict() { return "VERSION_CONFLICT".equals(type); }
        public boolean isLockFailed() { return "LOCK_FAILED".equals(type); }

        @Override
        public String toString() {
            return String.format("ProfileUpdateResult{success=%s, type=%s, version=%s->%s, message='%s'}", 
                success, type, oldVersion, newVersion, message);
        }
    }

    /**
     * 原子更新结果封装类
     */
    public static class AtomicUpdateResult {
        private final boolean success;
        private final String message;
        private final String type;
        private final Long oldVersion;
        private final Long newVersion;

        private AtomicUpdateResult(boolean success, String message, String type, Long oldVersion, Long newVersion) {
            this.success = success;
            this.message = message;
            this.type = type;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
        }

        public static AtomicUpdateResult success(String message, Long oldVersion, Long newVersion) {
            return new AtomicUpdateResult(true, message, "SUCCESS", oldVersion, newVersion);
        }

        public static AtomicUpdateResult failed(String message) {
            return new AtomicUpdateResult(false, message, "FAILED", null, null);
        }

        public static AtomicUpdateResult versionConflict(String message, Long expectedVersion, Long currentVersion) {
            return new AtomicUpdateResult(false, message, "VERSION_CONFLICT", expectedVersion, currentVersion);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getType() { return type; }
        public Long getOldVersion() { return oldVersion; }
        public Long getNewVersion() { return newVersion; }
        public boolean isVersionConflict() { return "VERSION_CONFLICT".equals(type); }
    }
}