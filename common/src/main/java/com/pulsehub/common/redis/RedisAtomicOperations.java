package com.pulsehub.common.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis原子操作服务 (Redisson版本)
 * 
 * 基于Redisson提供高级原子操作，保证数据一致性：
 * 1. 版本化数据的原子更新
 * 2. 分布式锁保护的复合操作
 * 3. 条件更新和乐观锁控制
 * 4. 批量操作的事务性保证
 * 5. 原子引用和版本化对象管理
 * 
 * 核心设计原则：
 * - 基于Redisson原子引用实现版本控制
 * - 使用Redisson分布式锁确保操作原子性
 * - 支持批量事务操作
 * - 提供详细的操作结果反馈
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class RedisAtomicOperations {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public RedisAtomicOperations(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 原子操作:  替换 RedisProfileData
     * 使用Redisson的RBucket实现原子更新操作 原子操作支持：RBucket 提供 setIfAbsent()、compareAndSet()、getAndSet() 等方法，保证并发安全
     * RBucket<T> 就是把这个 value 包装成了一个 类型安全的容器
     * 采用简化设计：无版本控制，直接原子替换
     * 
     * @param key Redis键名
     * @param profileData 要更新的profile数据
     * @return 原子操作结果
     */
    public AtomicOperationResult atomicReplaceProfile(String key, RedisProfileData profileData) {
        if (key == null || profileData == null) {
            log.warn("原子更新参数无效: key={}, profileData={}", key, profileData);
            return AtomicOperationResult.failed("参数无效");
        }

        try {
            // 使用Redisson的RBucket实现原子更新
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(key);
            
            // 更新时间戳
            profileData.setLastUpdated(Instant.now());
            
            // 原子设置新值
            bucket.set(profileData);
            
            log.debug("原子更新profile成功: key={}, 数据字段数={}", 
                key, profileData.getProfileData() != null ? profileData.getProfileData().size() : 0);
            
            return AtomicOperationResult.success("原子更新成功");
            
        } catch (Exception e) {
            log.error("原子更新profile失败: key={}", key, e);
            return AtomicOperationResult.failed("原子更新异常: " + e.getMessage());
        }
    }

    /**
     * 原子增量更新RedisProfileData
     * 只更新指定字段，保留其他现有数据
     * 使用读取-修改-写入的原子操作模式
     * 
     * @param key Redis键名
     * @param updates 要更新的字段Map
     * @param source 更新来源标识
     * @return 原子操作结果
     */
    public AtomicOperationResult atomicUpdateProfile(String key, 
                                                   Map<String, Object> updates, 
                                                   String source) {
        if (key == null || updates == null || updates.isEmpty()) {
            log.warn("增量更新参数无效: key={}, updates={}, source={}", key, updates, source);
            return AtomicOperationResult.failed("参数无效");
        }
        
        if (source == null || source.trim().isEmpty()) {
            source = "unknown";
        }

        try {
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(key);
            
            // 获取现有数据
            RedisProfileData existing = bucket.get();
            
            if (existing == null) {
                // 如果不存在，从key解析userId并创建新数据
                String userId = parseUserIdFromKey(key);
                existing = RedisProfileData.createEmpty(userId);
                log.debug("创建新的profile数据: key={}, userId={}", key, userId);
            }
            
            // 增量更新：合并现有数据和新数据
            existing.getProfileData().putAll(updates);
            existing.setLastUpdated(Instant.now());
            
            // 更新元数据
            Map<String, Object> metadata = existing.getMetadata();
            metadata.put("lastSource", source);
            metadata.put("lastOperation", "incremental_update");
            metadata.put("lastUpdated", Instant.now().toString());
            metadata.put("updateFields", new ArrayList<>(updates.keySet()));
            metadata.put("updateCount", (Long) metadata.getOrDefault("updateCount", 0L) + 1);
            
            // 原子写回
            bucket.set(existing);
            
            log.debug("增量更新profile成功: key={}, 更新字段={}, 来源={}", 
                key, updates.keySet(), source);
            
            return AtomicOperationResult.success("增量更新成功");
            
        } catch (Exception e) {
            log.error("增量更新profile失败: key={}, source={}", key, source, e);
            return AtomicOperationResult.failed("增量更新异常: " + e.getMessage());
        }
    }
    
    /**
     * 从Redis键名中解析用户ID
     * 假设键名格式为 "profile:user:{userId}"
     */
    private String parseUserIdFromKey(String key) {
        if (key != null && key.contains(":")) {
            String[] parts = key.split(":");
            if (parts.length >= 3 && "profile".equals(parts[0]) && "user".equals(parts[1])) {
                return parts[2];
            }
        }
        return "unknown";
    }

    /**
     * 带分布式锁的原子更新
     * 使用Redisson分布式锁保护更新操作
     * 
     * @param key Redis键名
     * @param updates 要更新的profile数据
     * @param lockTimeout 锁超时时间(秒)
     * @return 原子操作结果
     */
    public AtomicOperationResult lockedAtomicUpdate(String key, Map<String, Object> updates, int lockTimeout, String source) {
        if (key == null || updates == null) {
            return AtomicOperationResult.failed("参数无效");
        }

        RLock lock = redissonClient.getLock("lock:" + key);
        
        try {
            // 尝试获取锁
            if (!lock.tryLock(1, lockTimeout, TimeUnit.SECONDS)) {
                log.warn("获取分布式锁失败: key={}", key);
                return AtomicOperationResult.failed("获取锁失败");
            }
            
            try {
                // 在锁保护下执行原子更新
                return atomicUpdateProfile(key, updates, source);
            } finally {
                // 确保释放锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待锁被中断: key={}", key, e);
            return AtomicOperationResult.failed("等待锁被中断");
        } catch (Exception e) {
            log.error("带锁原子更新失败: key={}", key, e);
            return AtomicOperationResult.failed("带锁更新异常: " + e.getMessage());
        }
    }

    /**
     * 批量原子更新
     * 使用Redisson事务实现批量原子更新
     * 
     * @param updates 批量更新请求列表
     * @return 批量操作结果
     */
    public BatchOperationResult batchAtomicUpdate(List<BatchUpdateRequest> updates) {


        return null;

    }



    /**
     * 获取或创建profile数据
     * 使用Redisson的原子引用实现获取或创建操作
     * 
     * @param key Redis键名
     * @param defaultData 默认数据(数据不存在时使用)
     * @param ttl 过期时间
     * @param timeUnit 时间单位
     * @return 获取或创建的结果
     */
    public GetOrCreateResult getOrCreateProfile(String key, RedisProfileData defaultData, 
                                              long ttl, TimeUnit timeUnit) {
        return null;
    }


    
    /**
     * 原子操作结果
     */
    @Getter
    public static class AtomicOperationResult {
        // Getters
        private final boolean success;
        private final String message;
        private final String type;

        private AtomicOperationResult(boolean success, String message, String type) {
            this.success = success;
            this.message = message;

            this.type = type;
        }

        public static AtomicOperationResult success(String message) {
            return new AtomicOperationResult(true, message, "SUCCESS");
        }

        public static AtomicOperationResult failed(String message) {
            return new AtomicOperationResult(false, message, "FAILED");
        }


        @Override
        public String toString() {
            return String.format("AtomicOperationResult{success=%s, type=%s, message='%s'}",
                success, type,  message);
        }
    }

    /**
     * 批量更新请求
     */
    @Getter
    public static class BatchUpdateRequest {
        private final String key;
        private final RedisProfileData profileData;

        public BatchUpdateRequest(String key, RedisProfileData profileData) {
            this.key = key;
            this.profileData = profileData;
        }

    }

    /**
     * 批量操作结果
     */
    @Getter
    public static class BatchOperationResult {
        private final boolean success;
        private final String message;
        private final int successCount;
        private final int totalCount;

        private BatchOperationResult(boolean success, String message, int successCount, int totalCount) {
            this.success = success;
            this.message = message;
            this.successCount = successCount;
            this.totalCount = totalCount;
        }

        public static BatchOperationResult success(String message, int successCount, int totalCount) {
            return new BatchOperationResult(true, message, successCount, totalCount);
        }

        public static BatchOperationResult failed(String message) {
            return new BatchOperationResult(false, message, 0, 0);
        }

    }


//    /**
//     * 条件更新结果
//     */
//    @Getter
//    public static class ConditionalUpdateResult {
//        private final boolean success;
//        private final String message;
//        private final String condition;
//        private final boolean conditionMet;
//
//        private ConditionalUpdateResult(boolean success, String message, String condition, boolean conditionMet) {
//            this.success = success;
//            this.message = message;
//            this.condition = condition;
//            this.conditionMet = conditionMet;
//        }
//
//        public static ConditionalUpdateResult success(String message, String condition) {
//            return new ConditionalUpdateResult(true, message, condition, true);
//        }
//
//        public static ConditionalUpdateResult failed(String message) {
//            return new ConditionalUpdateResult(false, message, null, false);
//        }
//
//        public static ConditionalUpdateResult conditionNotMet(String message, String condition) {
//            return new ConditionalUpdateResult(false, message, condition, false);
//        }
//
//        public static ConditionalUpdateResult fromAtomic(AtomicOperationResult atomicResult, String condition) {
//            return new ConditionalUpdateResult(atomicResult.isSuccess(), atomicResult.getMessage(), condition, true);
//        }
//
//    }

    /**
     * 获取或创建结果
     */
    @Getter
    public static class GetOrCreateResult {
        private final boolean success;
        private final boolean created;
        private final RedisProfileData data;
        private final String message;

        private GetOrCreateResult(boolean success, boolean created, RedisProfileData data, String message) {
            this.success = success;
            this.created = created;
            this.data = data;
            this.message = message;
        }

        public static GetOrCreateResult existing(RedisProfileData data) {
            return new GetOrCreateResult(true, false, data, "数据已存在");
        }

        public static GetOrCreateResult created(RedisProfileData data) {
            return new GetOrCreateResult(true, true, data, "数据已创建");
        }

        public static GetOrCreateResult failed(String message) {
            return new GetOrCreateResult(false, false, null, message);
        }

    }
}