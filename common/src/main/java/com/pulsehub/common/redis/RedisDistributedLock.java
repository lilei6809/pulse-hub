package com.pulsehub.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于Redisson的分布式锁实现
 * 
 * 功能特性：
 * 1. 使用Redisson提供的高性能分布式锁
 * 2. 自动看门狗机制，防止锁意外过期
 * 3. 原生可重入锁支持
 * 4. 支持公平锁和非公平锁
 * 5. 提供简洁的API接口
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

    private final RedissonClient redissonClient;

    public RedisDistributedLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取分布式锁（非阻塞）
     * - 高并发场景，避免线程阻塞
     * - 失败后有其他处理逻辑
     * - 对响应时间要求严格
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @return 锁信息对象，包含锁状态和Redisson锁实例
     */
    public LockInfo tryNonBlockingLock(String lockKey, long expireTime, TimeUnit timeUnit) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            /**
             * 注意: 此处 tryLock() 中最大等待时间是 0, 立即返回结果，不等待
             * 如果锁被占用，立即返回失败
             * 所以是非阻塞的
             */
            boolean acquired = lock.tryLock(0, expireTime, timeUnit);
            
            if (acquired) {
                log.debug("成功获取分布式锁: key={}, expireTime={}{}",
                    lockKey, expireTime, timeUnit.name().toLowerCase());
                return LockInfo.acquired(lockKey, lock);
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
     * 尝试获取可重入锁（Redisson原生支持可重入）
     * 同一个线程可以多次获取同一把锁(同一个 userId)
     * 
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @return 锁信息对象
     */
    public LockInfo tryReentrantLock(String lockKey, long expireTime, TimeUnit timeUnit) {
        // Redisson的RLock原生就是可重入锁，直接使用tryLock方法
        return tryNonBlockingLock(lockKey, expireTime, timeUnit);
    }

    /**
     * 阻塞式获取分布式锁
     * 会等待直到获取成功或超时
     *   - 必须获取锁才能继续执行
     *   - 可以接受短暂等待
     *   - 业务逻辑要求互斥执行
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @param maxWaitTime 最大等待时间
     * @return 锁信息对象
     */
    public LockInfo tryBlockingLock(String lockKey, long expireTime, TimeUnit timeUnit, long maxWaitTime) {
        try {
            RLock lock = redissonClient.getLock(lockKey);

            /**
             * 注意: 此处 tryLock() 中是有 最大等待时间的,
             * 会等待指定时间直到获取成功或超时
             * 在等待期间线程被阻塞
             */
            boolean acquired = lock.tryLock(maxWaitTime, expireTime, timeUnit);
            
            if (acquired) {
                log.debug("成功获取分布式锁(阻塞模式): key={}, waitTime={}{}, expireTime={}{}",
                    lockKey, maxWaitTime, timeUnit.name().toLowerCase(),
                    expireTime, timeUnit.name().toLowerCase());
                return LockInfo.acquired(lockKey, lock);
            } else {
                log.warn("获取分布式锁超时: key={}, maxWaitTime={}{}", 
                    lockKey, maxWaitTime, timeUnit.name().toLowerCase());
                return LockInfo.failed(lockKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待分布式锁时被中断: key={}", lockKey);
            return LockInfo.failed(lockKey);
        } catch (Exception e) {
            log.error("获取分布式锁异常: key={}", lockKey, e);
            return LockInfo.failed(lockKey);
        }
    }

    /**
     * 释放分布式锁
     * Redisson自动保证只有锁的持有者才能释放
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
            RLock lock = lockInfo.getLock();
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("成功释放分布式锁: key={}", lockInfo.getLockKey());
                return true;
            } else {
                log.warn("释放分布式锁失败，锁未被当前线程持有: key={}", lockInfo.getLockKey());
                return false;
            }
        } catch (Exception e) {
            log.error("释放分布式锁异常: key={}", lockInfo.getLockKey(), e);
            return false;
        }
    }

    /**
     * 释放可重入锁（Redisson原生支持可重入）
     * 
     * @param lockInfo 锁信息对象
     * @return 是否成功释放
     */
    public boolean unlockReentrant(LockInfo lockInfo) {
        // Redisson的RLock原生就是可重入锁，直接使用unlock方法
        return unlock(lockInfo);
    }

    /**
     * 检查锁是否存在
     * 
     * @param lockKey 锁的键名
     * @return 锁是否存在
     */
    public boolean isLocked(String lockKey) {
        try {
            RLock lock = redissonClient.getLock(lockKey);
            return lock.isLocked();
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
            RLock lock = redissonClient.getLock(lockKey);
            if (lock.isLocked()) {
                lock.forceUnlock();
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
     * 获取公平锁（按FIFO顺序获取锁）
     * 
     * @param lockKey 锁的键名
     * @param expireTime 锁的过期时间
     * @param timeUnit 时间单位
     * @param maxWaitTime 最大等待时间
     * @return 锁信息对象
     */
    public LockInfo getFairLock(String lockKey, long expireTime, TimeUnit timeUnit, long maxWaitTime) {
        try {
            RLock lock = redissonClient.getFairLock(lockKey);
            // 阻塞
            boolean acquired = lock.tryLock(maxWaitTime, expireTime, timeUnit);
            
            if (acquired) {
                log.debug("成功获取公平锁: key={}, waitTime={}{}, expireTime={}{}",
                    lockKey, maxWaitTime, timeUnit.name().toLowerCase(),
                    expireTime, timeUnit.name().toLowerCase());
                return LockInfo.acquired(lockKey, lock);
            } else {
                log.warn("获取公平锁超时: key={}, maxWaitTime={}{}", 
                    lockKey, maxWaitTime, timeUnit.name().toLowerCase());
                return LockInfo.failed(lockKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待公平锁时被中断: key={}", lockKey);
            return LockInfo.failed(lockKey);
        } catch (Exception e) {
            log.error("获取公平锁异常: key={}", lockKey, e);
            return LockInfo.failed(lockKey);
        }
    }

    /**
     * 锁信息数据类
     * 封装锁的状态和Redisson锁实例
     */
    public static class LockInfo {
        private final String lockKey;
        private final RLock lock;
        private final boolean acquired;
        private final long acquiredAt;

        private LockInfo(String lockKey, RLock lock, boolean acquired) {
            this.lockKey = lockKey;
            this.lock = lock;
            this.acquired = acquired;
            this.acquiredAt = System.currentTimeMillis();
        }

        /**
         * 创建成功获取锁的信息对象
         */
        public static LockInfo acquired(String lockKey, RLock lock) {
            return new LockInfo(lockKey, lock, true);
        }

        /**
         * 创建获取锁失败的信息对象
         */
        public static LockInfo failed(String lockKey) {
            return new LockInfo(lockKey, null, false);
        }

        // Getters
        public String getLockKey() { return lockKey; }
        public RLock getLock() { return lock; }
        public boolean isAcquired() { return acquired; }
        public long getAcquiredAt() { return acquiredAt; }

        /**
         * 检查锁是否仍被当前线程持有
         */
        public boolean isHeldByCurrentThread() {
            return acquired && lock != null && lock.isHeldByCurrentThread();
        }

        /**
         * 获取锁的剩余生存时间（毫秒）
         */
        public long getRemainingTimeToLive() {
            if (!acquired || lock == null) return -1;
            try {
                return lock.remainTimeToLive();
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public String toString() {
            return String.format("LockInfo{key='%s', acquired=%s, heldByCurrentThread=%s}", 
                lockKey, acquired, isHeldByCurrentThread());
        }
    }
}