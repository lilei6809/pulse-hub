package com.pulsehub.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis分布式锁实现
 * 
 * 功能特性：
 * 1. 基于Redis的SET NX EX命令实现分布式锁
 * 2. 支持锁的自动过期，避免死锁
 * 3. 使用UUID确保锁的持有者身份验证
 * 4. 提供Lua脚本保证原子性操作
 * 5. 支持可重入锁机制
 * 
 * 使用场景：
 * - 用户profile更新时的并发控制
 * - 缓存更新的原子性保证
 * - 分布式任务的互斥执行
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class RedisDistributedLock {

    private final RedisTemplate<String, Object> redisTemplate;
    
    /**
     * Lua脚本：原子性释放锁
     * 只有锁的持有者才能释放锁，避免误解锁
     */
    private static final String UNLOCK_LUA_SCRIPT = 
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "return redis.call('del', KEYS[1]) " +
        "else " +
        "return 0 " +
        "end";
    
    /**
     * Lua脚本：可重入锁实现
     * 支持同一个线程多次获取同一把锁
     */
    private static final String REENTRANT_LOCK_LUA_SCRIPT = 
        "local key = KEYS[1] " +
        "local value = ARGV[1] " +
        "local expireTime = ARGV[2] " +
        "local currentValue = redis.call('get', key) " +
        "if currentValue == false then " +
        "  redis.call('setex', key, expireTime, value .. ':1') " +
        "  return 1 " +
        "elseif string.find(currentValue, value) == 1 then " +
        "  local count = tonumber(string.sub(currentValue, string.len(value) + 2)) " +
        "  redis.call('setex', key, expireTime, value .. ':' .. (count + 1)) " +
        "  return 1 " +
        "else " +
        "  return 0 " +
        "end";
    
    /**
     * Lua脚本：可重入锁释放
     * 只有计数器为0时才真正释放锁
     */
    private static final String REENTRANT_UNLOCK_LUA_SCRIPT = 
        "local key = KEYS[1] " +
        "local value = ARGV[1] " +
        "local currentValue = redis.call('get', key) " +
        "if currentValue == false then " +
        "  return 0 " +
        "elseif string.find(currentValue, value) == 1 then " +
        "  local count = tonumber(string.sub(currentValue, string.len(value) + 2)) " +
        "  if count > 1 then " +
        "    redis.call('set', key, value .. ':' .. (count - 1)) " +
        "    return 1 " +
        "  else " +
        "    return redis.call('del', key) " +
        "  end " +
        "else " +
        "  return 0 " +
        "end";

    public RedisDistributedLock(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取分布式锁（非阻塞）
     * 
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @return 锁信息对象，包含锁状态和持有者标识
     */
    public LockInfo tryLock(String lockKey, long expireTime, TimeUnit timeUnit) {
        String lockValue = generateLockValue();
        long expireSeconds = timeUnit.toSeconds(expireTime);
        
        try {
            // 使用SET NX EX命令原子性获取锁
            Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, expireTime, timeUnit);
            
            if (Boolean.TRUE.equals(success)) {
                log.debug("成功获取分布式锁: key={}, value={}, expireTime={}秒", 
                    lockKey, lockValue, expireSeconds);
                return LockInfo.acquired(lockKey, lockValue, expireSeconds);
            } else {
                log.debug("获取分布式锁失败，锁已被占用: key={}", lockKey);
                return LockInfo.failed(lockKey);
            }
        } catch (Exception e) {
            log.error("获取分布式锁异常: key={}", lockKey, e);
            return LockInfo.failed(lockKey);
        }
    }

    /**
     * 尝试获取可重入锁
     * 同一个线程可以多次获取同一把锁
     * 
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @return 锁信息对象
     */
    public LockInfo tryReentrantLock(String lockKey, long expireTime, TimeUnit timeUnit) {
        String lockValue = generateLockValue();
        long expireSeconds = timeUnit.toSeconds(expireTime);
        
        try {
            RedisScript<Long> script = RedisScript.of(REENTRANT_LOCK_LUA_SCRIPT, Long.class);
            Long result = redisTemplate.execute(script, 
                Collections.singletonList(lockKey), 
                lockValue, String.valueOf(expireSeconds));
            
            if (result != null && result == 1L) {
                log.debug("成功获取可重入锁: key={}, value={}", lockKey, lockValue);
                return LockInfo.acquired(lockKey, lockValue, expireSeconds);
            } else {
                log.debug("获取可重入锁失败: key={}", lockKey);
                return LockInfo.failed(lockKey);
            }
        } catch (Exception e) {
            log.error("获取可重入锁异常: key={}", lockKey, e);
            return LockInfo.failed(lockKey);
        }
    }

    /**
     * 阻塞式获取分布式锁
     * 会重试直到获取成功或超时
     * 
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @param maxWaitTime 最大等待时间
     * @return 锁信息对象
     */
    public LockInfo lock(String lockKey, long expireTime, TimeUnit timeUnit, long maxWaitTime) {
        long startTime = System.currentTimeMillis();
        long maxWaitMillis = timeUnit.toMillis(maxWaitTime);
        
        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            LockInfo lockInfo = tryLock(lockKey, expireTime, timeUnit);
            if (lockInfo.isAcquired()) {
                return lockInfo;
            }
            
            try {
                // 等待100ms后重试
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待分布式锁时被中断: key={}", lockKey);
                return LockInfo.failed(lockKey);
            }
        }
        
        log.warn("获取分布式锁超时: key={}, maxWaitTime={}ms", lockKey, maxWaitMillis);
        return LockInfo.failed(lockKey);
    }

    /**
     * 释放分布式锁
     * 使用Lua脚本保证原子性，只有锁的持有者才能释放
     * 
     * @param lockInfo 锁信息对象
     * @return 是否成功释放
     */
    public boolean unlock(LockInfo lockInfo) {
        if (lockInfo == null || !lockInfo.isAcquired()) {
            log.warn("尝试释放无效的锁信息: {}", lockInfo);
            return false;
        }
        
        try {
            RedisScript<Long> script = RedisScript.of(UNLOCK_LUA_SCRIPT, Long.class);
            Long result = redisTemplate.execute(script, 
                Collections.singletonList(lockInfo.getLockKey()), 
                lockInfo.getLockValue());
            
            boolean success = result != null && result == 1L;
            if (success) {
                log.debug("成功释放分布式锁: key={}, value={}", 
                    lockInfo.getLockKey(), lockInfo.getLockValue());
            } else {
                log.warn("释放分布式锁失败，可能已过期或被其他线程持有: key={}, value={}", 
                    lockInfo.getLockKey(), lockInfo.getLockValue());
            }
            return success;
        } catch (Exception e) {
            log.error("释放分布式锁异常: key={}, value={}", 
                lockInfo.getLockKey(), lockInfo.getLockValue(), e);
            return false;
        }
    }

    /**
     * 释放可重入锁
     * 
     * @param lockInfo 锁信息对象
     * @return 是否成功释放
     */
    public boolean unlockReentrant(LockInfo lockInfo) {
        if (lockInfo == null || !lockInfo.isAcquired()) {
            log.warn("尝试释放无效的可重入锁信息: {}", lockInfo);
            return false;
        }
        
        try {
            RedisScript<Long> script = RedisScript.of(REENTRANT_UNLOCK_LUA_SCRIPT, Long.class);
            Long result = redisTemplate.execute(script, 
                Collections.singletonList(lockInfo.getLockKey()), 
                lockInfo.getLockValue());
            
            boolean success = result != null && result >= 1L;
            if (success) {
                log.debug("成功释放可重入锁: key={}", lockInfo.getLockKey());
            } else {
                log.warn("释放可重入锁失败: key={}", lockInfo.getLockKey());
            }
            return success;
        } catch (Exception e) {
            log.error("释放可重入锁异常: key={}", lockInfo.getLockKey(), e);
            return false;
        }
    }

    /**
     * 检查锁是否存在
     * 
     * @param lockKey 锁的键名
     * @return 锁是否存在
     */
    public boolean isLocked(String lockKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));
        } catch (Exception e) {
            log.error("检查锁状态异常: key={}", lockKey, e);
            return false;
        }
    }

    /**
     * 强制释放锁（危险操作，仅用于清理）
     * 不验证锁的持有者，直接删除锁
     * 
     * @param lockKey 锁的键名
     * @return 是否成功删除
     */
    public boolean forceUnlock(String lockKey) {
        try {
            Boolean result = redisTemplate.delete(lockKey);
            if (Boolean.TRUE.equals(result)) {
                log.warn("强制释放分布式锁: key={}", lockKey);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("强制释放锁异常: key={}", lockKey, e);
            return false;
        }
    }

    /**
     * 生成唯一的锁值
     * 组合格式：线程ID:UUID:时间戳
     * 
     * @return 唯一锁值
     */
    private String generateLockValue() {
        return Thread.currentThread().getId() + ":" + 
               UUID.randomUUID().toString().replace("-", "") + ":" + 
               Instant.now().toEpochMilli();
    }

    /**
     * 锁信息数据类
     * 封装锁的状态和相关信息
     */
    public static class LockInfo {
        private final String lockKey;
        private final String lockValue;
        private final boolean acquired;
        private final long expireTime;
        private final long acquiredAt;

        private LockInfo(String lockKey, String lockValue, boolean acquired, long expireTime) {
            this.lockKey = lockKey;
            this.lockValue = lockValue;
            this.acquired = acquired;
            this.expireTime = expireTime;
            this.acquiredAt = System.currentTimeMillis();
        }

        /**
         * 创建成功获取锁的信息对象
         */
        public static LockInfo acquired(String lockKey, String lockValue, long expireTime) {
            return new LockInfo(lockKey, lockValue, true, expireTime);
        }

        /**
         * 创建获取锁失败的信息对象
         */
        public static LockInfo failed(String lockKey) {
            return new LockInfo(lockKey, null, false, 0);
        }

        // Getters
        public String getLockKey() { return lockKey; }
        public String getLockValue() { return lockValue; }
        public boolean isAcquired() { return acquired; }
        public long getExpireTime() { return expireTime; }
        public long getAcquiredAt() { return acquiredAt; }

        /**
         * 检查锁是否已过期（本地判断）
         */
        public boolean isExpired() {
            if (!acquired) return true;
            return System.currentTimeMillis() - acquiredAt > expireTime * 1000;
        }

        @Override
        public String toString() {
            return String.format("LockInfo{key='%s', acquired=%s, expireTime=%ds}", 
                lockKey, acquired, expireTime);
        }
    }
}