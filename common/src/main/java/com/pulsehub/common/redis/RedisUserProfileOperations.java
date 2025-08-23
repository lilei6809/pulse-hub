package com.pulsehub.common.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
public class RedisUserProfileOperations {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final RedisDistributedLock distributedLock;

    private final int MAX_RETRY_TIMES = 8;
    private final long BASE_TIME = 5L;
    private final long MAX_SLEEP_TIME = 100L;


    public RedisUserProfileOperations(RedissonClient redissonClient, RedisDistributedLock distributedLock) {
        this.redissonClient = redissonClient;
        this.distributedLock = distributedLock;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 批量更新专用的线程池:  有界线程池（队列+拒绝策略）
     *
     * CompletableFuture.supplyAsync 默认用全局 FJP（不可控）
     * 在高 QPS 批量时容易压满全局池，影响应用其他异步任务。
      */
    ExecutorService batchPool = new ThreadPoolExecutor(
            Math.min(8, Runtime.getRuntime().availableProcessors()),
            Math.min(32, Runtime.getRuntime().availableProcessors() * 2),
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000),
            new ThreadFactoryBuilder().setNameFormat("redis-batch-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

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
    public ProfileOperationResult atomicReplaceProfile(String key, RedisProfileData profileData) {
        if (key == null || profileData == null) {
            log.warn("原子更新参数无效: key={}, profileData={}", key, profileData);
            return ProfileOperationResult.failed("参数无效");
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
            
            return ProfileOperationResult.success("原子更新成功",profileData);
            
        } catch (Exception e) {
            log.error("原子更新profile失败: key={}", key, e);
            return ProfileOperationResult.failed("原子更新异常: " + e.getMessage());
        }
    }

    /**
     * 原子增量更新RedisProfileData
     * 只更新指定字段，保留其他现有数据
     * 使用 CAS 自旋实现 原子更新
     * @param key Redis键名
     * @param updates 要更新的字段Map
     * @param source 更新来源标识
     * @return 原子操作结果
     */
    public ProfileOperationResult atomicUpdateProfile(String key,
                                                      Map<String, Object> updates,
                                                      String source) {

        if (key == null || updates == null || updates.isEmpty()) {
            log.warn("增量更新参数无效: key={}, updates={}, source={}", key, updates, source);
            return ProfileOperationResult.failed("参数无效");
        }
        
        if (source == null || source.trim().isEmpty()) {
            source = "unknown";
        }

        try {
            RBucket<RedisProfileData> bucket = redissonClient.getBucket(key);
            Instant updateTime = Instant.now(); // 统一时间戳

            for (int attempt = 0; attempt < MAX_RETRY_TIMES; attempt++) {
                RedisProfileData expected = bucket.get();

                if (expected == null) {
                    // Redis中没有数据，使用setIfAbsent初始化数据
                    String userId = parseUserIdFromKey(key);
                    RedisProfileData newData = RedisProfileData.createEmpty(userId);
                    
                    // 应用更新到新数据
                    newData.getProfileData().putAll(updates);
                    newData.setLastUpdated(updateTime);
                    updateMetadata(newData, source, "Incremental_update", updates);
                    
                    // 尝试原子性地设置初始数据
                    if (bucket.setIfAbsent(newData)) {
                        log.debug("初始化新profile成功: key={}, userId={}, CAS attempt={}, source={}", key, userId, attempt,source);
                        return ProfileOperationResult.success("incremental update success", newData);
                    } else {
                        // 有其他线程已经设置了数据，重新开始循环
                        log.debug("初始化失败，其他线程已设置数据: attempt={}", attempt);
                        continue;
                    }
                }

                // 调试信息：记录原始expected
                log.debug("CAS attempt {}: expected对象 = {}, source={}", attempt, expected, source);

                RedisProfileData updated = RedisProfileData.deepCopy(expected);

                // 应用更新
                updated.getProfileData().putAll(updates);
                updated.setLastUpdated(updateTime);

                // 更新元数据
                updateMetadata(updated, source, "Incremental_update", updates);

                // 调试信息：记录updated对象
                log.debug("CAS attempt {}: updated对象 = {}, source = {}", attempt, updated, source);
                
                // 调试信息：检查对象是否equals
                log.debug("CAS attempt {}: expected.equals(updated) = {}", attempt, expected.equals(updated));

                /**
                 * compareAndSet 在 Redis 端对比的是序列化后的字节。
                 * 如果你使用 JSON 类序列化器，Map 的键顺序或浮点/时间序列化差异会导致语义相等但字节不同 → CAS 永远失败。
                 *
                 * 改成 二进制/确定性 序列化（Smile/CBOR、Kryo、FST），或配置 Jackson 排序字段、固定时区/格式；
                 *
                 * 比较"当前 Redis中的值是否还是 expected（即没有被其他线程修改），如果是就设置为 updated"
                 */
                if (bucket.compareAndSet(expected, updated)) {
                    log.warn("增量更新profile成功: key={}, 更新字段={}, 来源={}, CAS attempt={}",
                            key, updates.keySet(), source, attempt);
                    return ProfileOperationResult.success("incremental update success", updated);
                } else {
                    log.warn("source = {}, CAS失败 attempt {}: expected vs updated 序列化可能不同", source, attempt);
                    
                    // 重新获取当前Redis中的值，看看是否发生了变化
                    RedisProfileData currentInRedis = bucket.get();
                    log.warn("source = {}, CAS失败 attempt {}: Redis当前值 = {}", source, attempt, currentInRedis == null ? "null" : currentInRedis.toString());

                }

                // 失败退避
                Thread.sleep(Math.min(BASE_TIME << attempt, MAX_SLEEP_TIME));
            }

            return ProfileOperationResult.failed("CAS 冲突严重");

        } catch (Exception e) {
            log.error("增量更新profile失败: key={}, source={}", key, source, e);
            return ProfileOperationResult.failed("增量更新异常: " + e.getMessage());
        }

    }

    /**
     * 将 metadata 中某个 key 的 value 转为 long , 进行数学 increment
     * @return 更新后的值
     */
    private long incrMetaUpdateCounter(Map<String, Object> metadata, String key, long delta) {
        Object raw =  metadata.get(key);
        long cur = 0L;

        /**
         * raw instanceof Number n 表示“如果 raw 是 Number 类型，就把它自动强转并绑定到局部变量 n”
         * 如果不这样做, 需要 Number n = (Number) raw; cur = n.longValue();
         */
        if (raw instanceof Number n) {
            cur = n.longValue();
        } else if (raw instanceof String s) {
            try{
                cur = Long.parseLong(s);
            } catch (NumberFormatException ignore){

            }
        }

        long update = Math.addExact(cur, delta);

        metadata.put(key, update);
        return update;
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
    public ProfileOperationResult atomicUpdateProfileWithLock(String key, Map<String, Object> updates, int lockTimeout, String source) {
        if (key == null || updates == null) {
            return ProfileOperationResult.failed("参数无效");
        }

        if (source == null || source.trim().isEmpty()) {
            source = "unknown";
        }


        String userId = parseUserIdFromKey(key);

        String lockKey = RedisKeyUtils.getLockKey(userId);
        String profileKey = RedisKeyUtils.getProfileKey(userId);
        
        try {

            RedisDistributedLock.LockInfo lockInfo = distributedLock.tryNonBlockingLock(lockKey, lockTimeout, TimeUnit.SECONDS);

            if (!lockInfo.isAcquired()) {
                log.debug("获取分布式锁失败, userId={}, lockInfo={}", userId, lockInfo);
                return ProfileOperationResult.failed("获取锁失败");
            }

            // 获取锁
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
                            .build();
                } else {
                    // 创建新数据
                    updatedData = RedisProfileData.builder()
                            .profileData(new HashMap<>(updates))
                            .lastUpdated(Instant.now())

                            .build();


                }

                updateMetadata(updatedData, source, "Incremental_update", updates);

                bucket.set(updatedData);

                log.debug("安全更新profile成功: userId={}, 操作类型={}",
                        userId, existingData != null ? "update" : "create");

                return ProfileOperationResult.success(
                        String.format("安全更新profile成功: userId=%s, 操作类型=%s", userId, existingData != null ? "update" : "create"),
                        updatedData);

            } finally {
                // 释放锁
                distributedLock.unlock(lockInfo);
            }

        } catch (Exception e) {
            log.error("安全更新profile异常: userId={}", userId, e);
            return ProfileOperationResult.failed(String.format("安全更新profile异常: userId=%s, error msg=%s", userId, e));
        }

    }


    /**
     * 查询 redis 中的 user profile
     * @param userId
     * @return
     */
    public ProfileOperationResult getProfile(final String userId){
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
                return ProfileOperationResult.failed("Profile不存在");
            }

        } catch (Exception e) {
            log.error("获取profile异常: userId={}", userId, e);
            return ProfileOperationResult.failed("获取异常: " + e.getMessage());
        }
    }

    /**
     * 批量原子更新: 对每个 update 都执行 CAS自旋来避免写冲突
     *
     * @param updates 批量更新请求列表
     * @return 批量操作结果
     */
    public BatchProfileOperationResult batchAtomicUpdate(List<UpdateRequest> updates) {
        if (updates == null || updates.isEmpty()) {
            return BatchProfileOperationResult.failed("批量更新请求为空");
        }

        List<CompletableFuture<ProfileOperationResult>> futures = new ArrayList<>(updates.size());

        for (UpdateRequest request : updates) {
            // 修复：传递完整的key而不是userId
            String key = request.getKey();
            Map<String, Object> objectMap = request.getUpdates();
            String source = request.getSource();
            
            CompletableFuture<ProfileOperationResult> future =
                    CompletableFuture.supplyAsync(() -> 
                        atomicUpdateProfile(key, objectMap, source)
                            , batchPool
                    );
            futures.add(future);
        }

        // 等待所有操作完成
        List<ProfileOperationResult> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());

        int successCount = (int) results.stream().filter(ProfileOperationResult::isSuccess).count();
        int totalCount = updates.size();

        return BatchProfileOperationResult.builder()
                .success(successCount == totalCount) // 只有全部成功才返回true
                .successCount(successCount)
                .totalCount(totalCount)
                .message(String.format("批量更新完成: 成功 %d/%d", successCount, totalCount))
                .build();
    }




    /**
     * 使用 RedissonClient 提供的 transaction 实现批量更新
     * @param updates
     * @return
     */
    public BatchProfileOperationResult batchAtomicUpdateWithRedissonTnx(List<UpdateRequest> updates) {
        if (updates == null || updates.isEmpty()) {
            return BatchProfileOperationResult.failed("批量更新请求为空");
        }

        // 配置事务选项
        TransactionOptions options = TransactionOptions.defaults()
                .timeout(30, TimeUnit.SECONDS)    // 事务超时
                .responseTimeout(10, TimeUnit.SECONDS); // 响应超时


        // 启动 RTransaction 事务
        RTransaction transaction = redissonClient.createTransaction(options);
        AtomicInteger successCount = new AtomicInteger(0);

        try {


            for (UpdateRequest request : updates) {
                String key = request.getKey();

                // 获取事务内的bucket引用
                RBucket<RedisProfileData> bucket = transaction.getBucket(key);

                // 读取现有数据（在事务内）
                RedisProfileData existing = bucket.get();

                if (existing == null) {
                    // 创建新数据
                    String userId = parseUserIdFromKey(key);
                    existing = RedisProfileData.createEmpty(userId);
                }

                // 应用更新
                existing.getProfileData().putAll(request.getUpdates());
                existing.setLastUpdated(Instant.now());

                // 更新元数据
                updateMetadata(existing, request.getSource(), "batch_update", request.getUpdates());

                // 在事务内设置新值
                bucket.set(existing);

                successCount.incrementAndGet();
            }

            // 第二阶段：提交事务
            transaction.commit();

            int totalCount = updates.size();
            log.info("批量原子更新成功: 总数={}, 成功={}",
                    totalCount,  successCount);

            return BatchProfileOperationResult.builder()
                    .success(successCount.get() > 0) //
                    .successCount(successCount.get())
                    .totalCount(totalCount)
                    .message(String.format("批量更新完成: 成功 %d/%d", successCount.get(), totalCount))
                    .build();

        } catch (Exception e) {
            // 回滚事务
            try {
                transaction.rollback();
                log.warn("批量更新失败，已回滚事务: {}", e.getMessage());
            } catch (Exception rollbackError) {
                log.error("事务回滚失败", rollbackError);
            }

            return BatchProfileOperationResult.failed("批量更新异常: " + e.getMessage());
        }
    }


    /**
     * 更新 profile 的 metadata
     * @param existing 当前的 profile
     * @param source 当前更新的来源
     * @param operation 当前的操作类型
     * @param updates 更新条目
     */
    private void updateMetadata(RedisProfileData existing, String source, String operation, Map<String, Object> updates) {


        Map<String, Object> metadata = existing.getMetadata();
        metadata.put("source", source);
        metadata.put("lastOperation", existing.getMetadata().getOrDefault("lastOperation", "unknown"));
        metadata.put("operation", operation);
        metadata.put("updatedFields", new ArrayList<>(updates.keySet()));

        metadata.put("lastSource", existing.getMetadata().getOrDefault("source", "unknown"));

        incrMetaUpdateCounter(metadata, "updateCount", 1);

        existing.setMetadata(metadata);

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
     * 批量更新请求
     */
    @Getter
    public static class UpdateRequest {
        private final String key;
        private final Map<String, Object> updates;
        private final String source;

        public UpdateRequest(String key, Map<String, Object> updates, String source) {
            this.key = key;
            this.updates = updates;
            this.source = source;
        }

    }

    /**
     * 批量操作结果
     */
    @Getter
    @Builder
    public static class BatchProfileOperationResult {
        private final boolean success;
        private final String message;
        private final int successCount;
        private final int totalCount;

        private BatchProfileOperationResult(boolean success, String message, int successCount, int totalCount) {
            this.success = success;
            this.message = message;
            this.successCount = successCount;
            this.totalCount = totalCount;
        }

        public static BatchProfileOperationResult success(String message, int successCount, int totalCount) {
            return new BatchProfileOperationResult(true, message, successCount, totalCount);
        }

        public static BatchProfileOperationResult failed(String message) {
            return new BatchProfileOperationResult(false, message, 0, 0);
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