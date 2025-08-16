package com.pulsehub.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis原子操作服务
 * 
 * 提供基于Lua脚本的原子操作，保证数据一致性：
 * 1. 版本化数据的原子更新
 * 2. 分布式锁保护的复合操作
 * 3. 条件更新和乐观锁控制
 * 4. 批量操作的事务性保证
 * 5. 数据完整性验证和回滚
 * 
 * 核心设计原则：
 * - 所有操作都具备原子性
 * - 支持版本控制和冲突检测
 * - 提供详细的操作结果反馈
 * - 异常情况下的优雅降级
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class RedisAtomicOperations {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Lua脚本：版本化数据的原子更新
     * 功能：检查版本号，更新数据，递增版本
     */
    private static final String ATOMIC_UPDATE_LUA_SCRIPT = 
        "local key = KEYS[1] " +
        "local expectedVersion = tonumber(ARGV[1]) " +
        "local newData = ARGV[2] " +
        "local currentTime = ARGV[3] " +
        "" +
        "-- 获取当前数据 " +
        "local currentData = redis.call('get', key) " +
        "if currentData == false then " +
        "  -- 数据不存在，创建新数据(版本号为1) " +
        "  if expectedVersion == 0 then " +
        "    redis.call('set', key, newData) " +
        "    return {1, 'created', 0, 1} " +
        "  else " +
        "    return {0, 'not_found', 0, 0} " +
        "  end " +
        "end " +
        "" +
        "-- 解析当前数据获取版本号 " +
        "local dataTable = cjson.decode(currentData) " +
        "local currentVersion = tonumber(dataTable.version or 0) " +
        "" +
        "-- 版本检查 " +
        "if expectedVersion ~= currentVersion then " +
        "  return {0, 'version_conflict', expectedVersion, currentVersion} " +
        "end " +
        "" +
        "-- 更新数据和版本号 " +
        "local newDataTable = cjson.decode(newData) " +
        "newDataTable.version = currentVersion + 1 " +
        "newDataTable.lastUpdated = currentTime " +
        "" +
        "-- 保存更新后的数据 " +
        "redis.call('set', key, cjson.encode(newDataTable)) " +
        "return {1, 'updated', currentVersion, currentVersion + 1}";

    /**
     * Lua脚本：带锁的原子更新
     * 功能：获取锁，检查版本，更新数据，释放锁
     */
    private static final String LOCKED_UPDATE_LUA_SCRIPT = 
        "local dataKey = KEYS[1] " +
        "local lockKey = KEYS[2] " +
        "local expectedVersion = tonumber(ARGV[1]) " +
        "local newData = ARGV[2] " +
        "local lockValue = ARGV[3] " +
        "local lockExpire = tonumber(ARGV[4]) " +
        "local currentTime = ARGV[5] " +
        "" +
        "-- 尝试获取锁 " +
        "local lockResult = redis.call('set', lockKey, lockValue, 'EX', lockExpire, 'NX') " +
        "if not lockResult then " +
        "  return {0, 'lock_failed', 0, 0, 'failed_to_acquire_lock'} " +
        "end " +
        "" +
        "-- 获取当前数据 " +
        "local currentData = redis.call('get', dataKey) " +
        "if currentData == false then " +
        "  redis.call('del', lockKey) " +
        "  return {0, 'not_found', 0, 0, 'data_not_found'} " +
        "end " +
        "" +
        "-- 解析版本号 " +
        "local dataTable = cjson.decode(currentData) " +
        "local currentVersion = tonumber(dataTable.version or 0) " +
        "" +
        "-- 版本检查 " +
        "if expectedVersion ~= currentVersion then " +
        "  redis.call('del', lockKey) " +
        "  return {0, 'version_conflict', expectedVersion, currentVersion, 'version_mismatch'} " +
        "end " +
        "" +
        "-- 更新数据 " +
        "local newDataTable = cjson.decode(newData) " +
        "newDataTable.version = currentVersion + 1 " +
        "newDataTable.lastUpdated = currentTime " +
        "" +
        "-- 保存数据并释放锁 " +
        "redis.call('set', dataKey, cjson.encode(newDataTable)) " +
        "redis.call('del', lockKey) " +
        "" +
        "return {1, 'updated', currentVersion, currentVersion + 1, 'success'}";

    /**
     * Lua脚本：批量原子更新
     * 功能：对多个键执行原子更新，要么全部成功，要么全部失败
     */
    private static final String BATCH_UPDATE_LUA_SCRIPT = 
        "local numKeys = tonumber(ARGV[1]) " +
        "local currentTime = ARGV[2] " +
        "" +
        "-- 收集所有更新操作 " +
        "local updates = {} " +
        "for i = 1, numKeys do " +
        "  local key = KEYS[i] " +
        "  local expectedVersion = tonumber(ARGV[2 + i]) " +
        "  local newData = ARGV[2 + numKeys + i] " +
        "  " +
        "  updates[i] = {key = key, expectedVersion = expectedVersion, newData = newData} " +
        "end " +
        "" +
        "-- 第一阶段：检查所有版本号 " +
        "for i = 1, numKeys do " +
        "  local update = updates[i] " +
        "  local currentData = redis.call('get', update.key) " +
        "  " +
        "  if currentData == false then " +
        "    return {0, 'batch_failed', 'data_not_found', update.key} " +
        "  end " +
        "  " +
        "  local dataTable = cjson.decode(currentData) " +
        "  local currentVersion = tonumber(dataTable.version or 0) " +
        "  " +
        "  if update.expectedVersion ~= currentVersion then " +
        "    return {0, 'batch_failed', 'version_conflict', update.key, update.expectedVersion, currentVersion} " +
        "  end " +
        "  " +
        "  updates[i].currentVersion = currentVersion " +
        "end " +
        "" +
        "-- 第二阶段：执行所有更新 " +
        "local successCount = 0 " +
        "for i = 1, numKeys do " +
        "  local update = updates[i] " +
        "  local newDataTable = cjson.decode(update.newData) " +
        "  newDataTable.version = update.currentVersion + 1 " +
        "  newDataTable.lastUpdated = currentTime " +
        "  " +
        "  redis.call('set', update.key, cjson.encode(newDataTable)) " +
        "  successCount = successCount + 1 " +
        "end " +
        "" +
        "return {1, 'batch_success', successCount}";

    public RedisAtomicOperations(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 原子更新RedisProfileData
     * 检查版本号匹配后更新数据，保证操作的原子性
     * 
     * @param key Redis键名
     * @param profileData 要更新的profile数据
     * @param expectedVersion 期望的版本号
     * @return 原子操作结果
     */
    public AtomicOperationResult atomicUpdateProfile(String key, RedisProfileData profileData, Long expectedVersion) {
        if (key == null || profileData == null || expectedVersion == null) {
            log.warn("原子更新参数无效: key={}, profileData={}, expectedVersion={}", 
                key, profileData, expectedVersion);
            return AtomicOperationResult.failed("参数无效");
        }

        try {
            // 序列化profile数据
            String serializedData = objectMapper.writeValueAsString(profileData);
            String currentTime = String.valueOf(System.currentTimeMillis());

            // 执行Lua脚本
            RedisScript<List> script = RedisScript.of(ATOMIC_UPDATE_LUA_SCRIPT, List.class);
            List<Object> result = redisTemplate.execute(script, 
                Collections.singletonList(key), 
                expectedVersion.toString(), serializedData, currentTime);

            return parseAtomicResult(result, "atomic_update", key);

        } catch (JsonProcessingException e) {
            log.error("序列化profile数据失败: key={}", key, e);
            return AtomicOperationResult.failed("数据序列化失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("执行原子更新失败: key={}, expectedVersion={}", key, expectedVersion, e);
            return AtomicOperationResult.failed("原子更新异常: " + e.getMessage());
        }
    }

    /**
     * 带分布式锁的原子更新
     * 先获取分布式锁，再执行版本检查和数据更新
     * 
     * @param key Redis键名
     * @param profileData 要更新的profile数据
     * @param expectedVersion 期望的版本号
     * @param lockTimeout 锁超时时间(秒)
     * @return 原子操作结果
     */
    public AtomicOperationResult lockedAtomicUpdate(String key, RedisProfileData profileData, 
                                                   Long expectedVersion, int lockTimeout) {
        if (key == null || profileData == null || expectedVersion == null) {
            return AtomicOperationResult.failed("参数无效");
        }

        String lockKey = "lock:" + key;
        String lockValue = UUID.randomUUID().toString();

        try {
            String serializedData = objectMapper.writeValueAsString(profileData);
            String currentTime = String.valueOf(System.currentTimeMillis());

            RedisScript<List> script = RedisScript.of(LOCKED_UPDATE_LUA_SCRIPT, List.class);
            List<Object> result = redisTemplate.execute(script, 
                Arrays.asList(key, lockKey),
                expectedVersion.toString(), serializedData, lockValue, 
                String.valueOf(lockTimeout), currentTime);

            return parseAtomicResult(result, "locked_update", key);

        } catch (JsonProcessingException e) {
            log.error("序列化数据失败: key={}", key, e);
            return AtomicOperationResult.failed("数据序列化失败");
        } catch (Exception e) {
            log.error("带锁原子更新失败: key={}, expectedVersion={}", key, expectedVersion, e);
            return AtomicOperationResult.failed("带锁更新异常: " + e.getMessage());
        }
    }

    /**
     * 批量原子更新
     * 对多个profile数据执行原子更新，要么全部成功，要么全部回滚
     * 
     * @param updates 批量更新请求列表
     * @return 批量操作结果
     */
    public BatchOperationResult batchAtomicUpdate(List<BatchUpdateRequest> updates) {
        if (updates == null || updates.isEmpty()) {
            return BatchOperationResult.failed("更新列表为空");
        }

        try {
            List<String> keys = new ArrayList<>();
            List<String> args = new ArrayList<>();
            
            // 准备参数
            args.add(String.valueOf(updates.size())); // 键的数量
            args.add(String.valueOf(System.currentTimeMillis())); // 当前时间

            // 添加版本号
            for (BatchUpdateRequest update : updates) {
                keys.add(update.getKey());
                args.add(update.getExpectedVersion().toString());
            }

            // 添加序列化数据
            for (BatchUpdateRequest update : updates) {
                String serializedData = objectMapper.writeValueAsString(update.getProfileData());
                args.add(serializedData);
            }

            // 执行批量更新脚本
            RedisScript<List> script = RedisScript.of(BATCH_UPDATE_LUA_SCRIPT, List.class);
            List<Object> result = redisTemplate.execute(script, keys, args.toArray());

            return parseBatchResult(result, updates.size());

        } catch (JsonProcessingException e) {
            log.error("批量更新数据序列化失败", e);
            return BatchOperationResult.failed("数据序列化失败");
        } catch (Exception e) {
            log.error("批量原子更新失败: updateCount={}", updates.size(), e);
            return BatchOperationResult.failed("批量更新异常: " + e.getMessage());
        }
    }

    /**
     * 条件更新：仅当满足特定条件时才更新
     * 
     * @param key Redis键名
     * @param profileData 新的profile数据
     * @param condition 更新条件
     * @return 条件更新结果
     */
    public ConditionalUpdateResult conditionalUpdate(String key, RedisProfileData profileData, 
                                                    UpdateCondition condition) {
        try {
            // 先获取当前数据
            Object currentObj = redisTemplate.opsForValue().get(key);
            if (currentObj == null) {
                return ConditionalUpdateResult.failed("数据不存在");
            }

            RedisProfileData currentData = objectMapper.convertValue(currentObj, RedisProfileData.class);
            
            // 检查条件
            if (!condition.test(currentData)) {
                return ConditionalUpdateResult.conditionNotMet("更新条件不满足", condition.getDescription());
            }

            // 执行原子更新
            AtomicOperationResult atomicResult = atomicUpdateProfile(key, profileData, currentData.getVersion());
            
            return ConditionalUpdateResult.fromAtomic(atomicResult, condition.getDescription());

        } catch (Exception e) {
            log.error("条件更新失败: key={}", key, e);
            return ConditionalUpdateResult.failed("条件更新异常: " + e.getMessage());
        }
    }

    /**
     * 获取或创建profile数据
     * 如果数据不存在则创建，如果存在则返回当前数据
     * 
     * @param key Redis键名
     * @param defaultData 默认数据(数据不存在时使用)
     * @param ttl 过期时间
     * @param timeUnit 时间单位
     * @return 获取或创建的结果
     */
    public GetOrCreateResult getOrCreateProfile(String key, RedisProfileData defaultData, 
                                              long ttl, TimeUnit timeUnit) {
        try {
            // 尝试获取现有数据
            Object existing = redisTemplate.opsForValue().get(key);
            if (existing != null) {
                RedisProfileData profileData = objectMapper.convertValue(existing, RedisProfileData.class);
                return GetOrCreateResult.existing(profileData);
            }

            // 数据不存在，创建新数据
            redisTemplate.opsForValue().set(key, defaultData, ttl, timeUnit);
            log.debug("创建新的profile数据: key={}, ttl={}秒", key, timeUnit.toSeconds(ttl));
            
            return GetOrCreateResult.created(defaultData);

        } catch (Exception e) {
            log.error("获取或创建profile数据失败: key={}", key, e);
            return GetOrCreateResult.failed("操作异常: " + e.getMessage());
        }
    }

    /**
     * 解析原子操作结果
     */
    private AtomicOperationResult parseAtomicResult(List<Object> result, String operation, String key) {
        if (result == null || result.size() < 4) {
            return AtomicOperationResult.failed("脚本返回结果格式错误");
        }

        try {
            int success = ((Number) result.get(0)).intValue();
            String message = result.get(1).toString();
            Long oldVersion = ((Number) result.get(2)).longValue();
            Long newVersion = ((Number) result.get(3)).longValue();

            if (success == 1) {
                log.debug("原子操作成功: operation={}, key={}, version {} -> {}", 
                    operation, key, oldVersion, newVersion);
                return AtomicOperationResult.success(message, oldVersion, newVersion);
            } else {
                log.warn("原子操作失败: operation={}, key={}, message={}, expectedVersion={}, currentVersion={}", 
                    operation, key, message, oldVersion, newVersion);
                return AtomicOperationResult.conflict(message, oldVersion, newVersion);
            }
        } catch (Exception e) {
            log.error("解析原子操作结果失败: operation={}, key={}", operation, key, e);
            return AtomicOperationResult.failed("结果解析异常: " + e.getMessage());
        }
    }

    /**
     * 解析批量操作结果
     */
    private BatchOperationResult parseBatchResult(List<Object> result, int expectedCount) {
        if (result == null || result.size() < 3) {
            return BatchOperationResult.failed("批量操作结果格式错误");
        }

        try {
            int success = ((Number) result.get(0)).intValue();
            String message = result.get(1).toString();

            if (success == 1) {
                int successCount = ((Number) result.get(2)).intValue();
                log.info("批量原子更新成功: expectedCount={}, successCount={}", expectedCount, successCount);
                return BatchOperationResult.success(message, successCount, expectedCount);
            } else {
                String errorType = result.size() > 2 ? result.get(2).toString() : "unknown";
                String errorKey = result.size() > 3 ? result.get(3).toString() : "unknown";
                
                log.warn("批量原子更新失败: message={}, errorType={}, errorKey={}", message, errorType, errorKey);
                return BatchOperationResult.failed(String.format("%s (type=%s, key=%s)", message, errorType, errorKey));
            }
        } catch (Exception e) {
            log.error("解析批量操作结果失败", e);
            return BatchOperationResult.failed("结果解析异常: " + e.getMessage());
        }
    }

    // 内部类定义...
    
    /**
     * 原子操作结果
     */
    public static class AtomicOperationResult {
        private final boolean success;
        private final String message;
        private final Long oldVersion;
        private final Long newVersion;
        private final String type;

        private AtomicOperationResult(boolean success, String message, Long oldVersion, Long newVersion, String type) {
            this.success = success;
            this.message = message;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.type = type;
        }

        public static AtomicOperationResult success(String message, Long oldVersion, Long newVersion) {
            return new AtomicOperationResult(true, message, oldVersion, newVersion, "SUCCESS");
        }

        public static AtomicOperationResult failed(String message) {
            return new AtomicOperationResult(false, message, null, null, "FAILED");
        }

        public static AtomicOperationResult conflict(String message, Long expectedVersion, Long currentVersion) {
            return new AtomicOperationResult(false, message, expectedVersion, currentVersion, "CONFLICT");
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Long getOldVersion() { return oldVersion; }
        public Long getNewVersion() { return newVersion; }
        public String getType() { return type; }
        public boolean isConflict() { return "CONFLICT".equals(type); }

        @Override
        public String toString() {
            return String.format("AtomicOperationResult{success=%s, type=%s, version=%s->%s, message='%s'}", 
                success, type, oldVersion, newVersion, message);
        }
    }

    /**
     * 批量更新请求
     */
    public static class BatchUpdateRequest {
        private final String key;
        private final RedisProfileData profileData;
        private final Long expectedVersion;

        public BatchUpdateRequest(String key, RedisProfileData profileData, Long expectedVersion) {
            this.key = key;
            this.profileData = profileData;
            this.expectedVersion = expectedVersion;
        }

        public String getKey() { return key; }
        public RedisProfileData getProfileData() { return profileData; }
        public Long getExpectedVersion() { return expectedVersion; }
    }

    /**
     * 批量操作结果
     */
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

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getSuccessCount() { return successCount; }
        public int getTotalCount() { return totalCount; }
    }

    /**
     * 更新条件接口
     */
    public interface UpdateCondition {
        boolean test(RedisProfileData currentData);
        String getDescription();
    }

    /**
     * 条件更新结果
     */
    public static class ConditionalUpdateResult {
        private final boolean success;
        private final String message;
        private final String condition;
        private final boolean conditionMet;

        private ConditionalUpdateResult(boolean success, String message, String condition, boolean conditionMet) {
            this.success = success;
            this.message = message;
            this.condition = condition;
            this.conditionMet = conditionMet;
        }

        public static ConditionalUpdateResult success(String message, String condition) {
            return new ConditionalUpdateResult(true, message, condition, true);
        }

        public static ConditionalUpdateResult failed(String message) {
            return new ConditionalUpdateResult(false, message, null, false);
        }

        public static ConditionalUpdateResult conditionNotMet(String message, String condition) {
            return new ConditionalUpdateResult(false, message, condition, false);
        }

        public static ConditionalUpdateResult fromAtomic(AtomicOperationResult atomicResult, String condition) {
            return new ConditionalUpdateResult(atomicResult.isSuccess(), atomicResult.getMessage(), condition, true);
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getCondition() { return condition; }
        public boolean isConditionMet() { return conditionMet; }
    }

    /**
     * 获取或创建结果
     */
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

        public boolean isSuccess() { return success; }
        public boolean isCreated() { return created; }
        public RedisProfileData getData() { return data; }
        public String getMessage() { return message; }
    }
}