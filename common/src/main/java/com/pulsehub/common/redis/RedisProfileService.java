package com.pulsehub.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.pulsehub.common.redis.RedisDistributedLock.*;

/**
 * Redis Profile 服务
 * 
 * 提供用户Profile数据的Redis操作服务：
 * 1. 原子更新Profile数据
 * 2. 分布式锁保护的安全操作
 * 3. Profile数据的获取和存在性检查
 * 4. 数据清理和删除操作
 * 
 * 特性：
 * - 无版本控制的轻量级操作
 * - 分布式锁保证并发安全
 * - 统一的错误处理和日志记录
 * - 灵活的TTL管理
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class RedisProfileService {

    private final RedissonClient redissonClient;
    private final RedisDistributedLock distributedLock;

    @Autowired
    public RedisProfileService(RedissonClient redissonClient, RedisDistributedLock distributedLock) {
        this.redissonClient = redissonClient;
        this.distributedLock = distributedLock;
    }

    /**
     * 原子更新用户Profile数据
     * 不使用版本控制，直接更新数据
     * 
     * @param userId 用户ID
     * @param updates 要更新的数据
     * @param source 更新来源标识
     * @return 更新结果
     */
    public ProfileOperationResult atomicUpdateProfile(final String userId, 
                                                      final Map<String, Object> updates, 
                                                      final String source) {
        if (userId == null || updates == null || updates.isEmpty()) {
            log.warn("更新参数无效: userId={}, updates={}", userId, updates);
            return ProfileOperationResult.failed("参数无效");
        }

        String profileKey = RedisKeyUtils.getProfileKey(userId);
        
        try {
            // 构建新的profile数据
            RedisProfileData profileData = RedisProfileData.builder()
                .profileData(new HashMap<>(updates))
                .lastUpdated(Instant.now())
                .metadata(Map.of(
                    "source", source, 
                    "operation", "atomic_update",
                    "userId", userId,
                    "updateTime", Instant.now().toString()
                ))
                .build();

            // 直接存储到Redis
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(profileKey);
            bucket.set(profileData);
            
            log.debug("原子更新profile成功: userId={}, 更新字段数={}, 来源={}", 
                userId, updates.size(), source);
            
            return ProfileOperationResult.success("原子更新成功", profileData);

        } catch (Exception e) {
            log.error("原子更新profile异常: userId={}, source={}", userId, source, e);
            return ProfileOperationResult.failed("原子更新异常: " + e.getMessage());
        }
    }

    /**
     * 带分布式锁的安全更新
     * 适用于高并发场景，确保数据一致性
     * 
     * @param userId 用户ID
     * @param updates 要更新的数据
     * @param source 更新来源
     * @param lockTimeoutSeconds 锁超时时间(秒)
     * @return 更新结果
     */
    public ProfileOperationResult safeUpdateProfile(final String userId, 
                                                   final Map<String, Object> updates, 
                                                   final String source, 
                                                   final int lockTimeoutSeconds) {
        if (userId == null || updates == null || updates.isEmpty()) {
            return ProfileOperationResult.failed("参数无效");
        }

        String lockKey = RedisKeyUtils.getLockKey(userId);
        String profileKey = RedisKeyUtils.getProfileKey(userId);

        try {
            LockInfo lockInfo = distributedLock.tryNonBlockingLock(lockKey, lockTimeoutSeconds, TimeUnit.SECONDS);

            // 获取分布式锁
            if (!lockInfo.isAcquired()) {
                log.warn("获取分布式锁失败: userId={}", userId);
                return ProfileOperationResult.lockFailed("获取锁失败");
            }

            try {
                // 在锁保护下获取现有数据
                RBucket<RedisProfileData> bucket = redissonClient.getBucket(profileKey);
                RedisProfileData existingData = bucket.get();

                RedisProfileData updatedData;
                if (existingData != null) {
                    // 更新现有数据
                    Map<String, Object> mergedData = new HashMap<>(existingData.getProfileData());
                    mergedData.putAll(updates);
                    
                    updatedData = RedisProfileData.builder()
                        .profileData(mergedData)
                        .createdAt(existingData.getCreatedAt())
                        .lastUpdated(Instant.now())
                        .metadata(Map.of(
                            "source", source,
                            "operation", "safe_update",
                            "userId", userId,
                            "updateTime", Instant.now().toString(),
                            "previousSource", 
                            existingData.getMetadata().getOrDefault("source", "unknown")
                        ))
                        .build();
                } else {
                    // 创建新数据
                    updatedData = RedisProfileData.builder()
                        .profileData(new HashMap<>(updates))
                        .lastUpdated(Instant.now())
                        .metadata(Map.of(
                            "source", source,
                            "operation", "safe_create",
                            "userId", userId,
                            "createTime", Instant.now().toString()
                        ))
                        .build();
                }

                bucket.set(updatedData);
                
                log.debug("安全更新profile成功: userId={}, 操作类型={}", 
                    userId, existingData != null ? "update" : "create");
                
                return ProfileOperationResult.success("安全更新成功", updatedData);

            } finally {
                distributedLock.unlock(lockInfo);
            }

        } catch (Exception e) {
            log.error("安全更新profile异常: userId={}", userId, e);
            return ProfileOperationResult.failed("安全更新异常: " + e.getMessage());
        }
    }

    /**
     * 获取用户Profile数据
     * 
     * @param userId 用户ID
     * @return Profile数据
     */
    public ProfileOperationResult getProfile(final String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return ProfileOperationResult.failed("用户ID不能为空");
        }

        String profileKey = RedisKeyUtils.getProfileKey(userId);
        
        try {
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(profileKey);
            RedisProfileData profileData = bucket.get();
            
            if (profileData != null) {
                log.debug("获取profile成功: userId={}, 数据年龄={}秒", 
                    userId, profileData.getAgeInSeconds());
                return ProfileOperationResult.success("获取成功", profileData);
            } else {
                log.debug("profile不存在: userId={}", userId);
                return ProfileOperationResult.notFound("Profile不存在");
            }

        } catch (Exception e) {
            log.error("获取profile异常: userId={}", userId, e);
            return ProfileOperationResult.failed("获取异常: " + e.getMessage());
        }
    }

    /**
     * 检查Profile是否存在
     * 
     * @param userId 用户ID
     * @return 是否存在
     */
    public boolean profileExists(final String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }

        try {
            String profileKey = RedisKeyUtils.getProfileKey(userId);
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(profileKey);
            return bucket.isExists();
        } catch (Exception e) {
            log.error("检查profile存在性异常: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 删除用户Profile数据
     * 使用分布式锁保护删除操作
     * 
     * @param userId 用户ID
     * @param reason 删除原因
     * @return 删除结果
     */
    public ProfileOperationResult deleteProfile(final String userId, 
                                               final String reason) {
        if (userId == null || userId.trim().isEmpty()) {
            return ProfileOperationResult.failed("用户ID不能为空");
        }

        String lockKey = RedisKeyUtils.getLockKey(userId);
        String profileKey = RedisKeyUtils.getProfileKey(userId);

        try {
            LockInfo lockInfo = distributedLock.tryNonBlockingLock(lockKey, 10, TimeUnit.SECONDS);
            // 获取分布式锁
            if (!lockInfo.isAcquired()) {
                return ProfileOperationResult.lockFailed("获取删除锁失败");
            }

            try {
                RBucket<RedisProfileData> bucket = redissonClient.getBucket(profileKey);
                boolean existed = bucket.isExists();
                
                if (existed) {
                    bucket.delete();
                    log.info("删除profile成功: userId={}, 原因={}", userId, reason);
                    return ProfileOperationResult.success("删除成功", null);
                } else {
                    log.debug("profile不存在，无需删除: userId={}", userId);
                    return ProfileOperationResult.notFound("Profile不存在，无需删除");
                }

            } finally {
                distributedLock.unlock(lockInfo);
            }

        } catch (Exception e) {
            log.error("删除profile异常: userId={}, reason={}", userId, reason, e);
            return ProfileOperationResult.failed("删除异常: " + e.getMessage());
        }
    }

    /**
     * 设置Profile数据的TTL
     * 
     * @param userId 用户ID
     * @param ttl TTL值
     * @param timeUnit 时间单位
     * @return 操作结果
     */
    public ProfileOperationResult setProfileTTL(final String userId, 
                                               final long ttl, 
                                               final TimeUnit timeUnit) {
        if (userId == null || userId.trim().isEmpty()) {
            return ProfileOperationResult.failed("用户ID不能为空");
        }

        try {
            String profileKey = RedisKeyUtils.getProfileKey(userId);
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(profileKey);

            if (bucket.isExists()) {
                bucket.expire(ttl, timeUnit);
                log.debug("设置profile TTL成功: userId={}, ttl={}秒", 
                    userId, timeUnit.toSeconds(ttl));
                return ProfileOperationResult.success("TTL设置成功", null);
            } else {
                return ProfileOperationResult.notFound("Profile不存在，无法设置TTL");
            }

        } catch (Exception e) {
            log.error("设置profile TTL异常: userId={}", userId, e);
            return ProfileOperationResult.failed("TTL设置异常: " + e.getMessage());
        }
    }
}