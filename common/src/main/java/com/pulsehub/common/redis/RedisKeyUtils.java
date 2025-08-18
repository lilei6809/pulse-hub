package com.pulsehub.common.redis;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis键名工具类
 * 
 * 提供统一的Redis键名生成规则和管理功能：
 * 1. 用户Profile数据的键名规范
 * 2. 分布式锁的键名规范
 * 3. 缓存键名的统一管理
 * 4. 键名模式的验证和解析
 * 
 * 键名规范：
 * - Profile数据: profile:user:{userId}
 * - 分布式锁: lock:profile:{userId}
 * - 原子操作: atomic:profile:user:{userId}
 * - 临时数据: temp:profile:{userId}:{timestamp}
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@Slf4j
public final class RedisKeyUtils {

    // 键名前缀常量
    private static final String PROFILE_PREFIX = "profile:user:";
    private static final String LOCK_PREFIX = "lock:profile:";
    private static final String ATOMIC_PREFIX = "atomic:profile:user:";
    private static final String TEMP_PREFIX = "temp:profile:";
    
    // 防止实例化
    private RedisKeyUtils() {
        throw new UnsupportedOperationException("RedisKeyUtils is a utility class");
    }

    /**
     * 获取用户profile的Redis键名
     * 统一键名规范：profile:user:{userId}
     * 
     * @param userId 用户ID
     * @return Redis键名
     * @throws IllegalArgumentException 如果userId为空
     */
    public static String getProfileKey(String userId) {
        validateUserId(userId);
        return PROFILE_PREFIX + userId;
    }

    /**
     * 获取用户锁的键名
     * 统一锁键规范：lock:profile:{userId}
     * 
     * @param userId 用户ID
     * @return 锁键名
     * @throws IllegalArgumentException 如果userId为空
     */
    public static String getLockKey(String userId) {
        validateUserId(userId);
        return LOCK_PREFIX + userId;
    }

    /**
     * 获取原子操作的键名
     * 原子操作键规范：atomic:profile:user:{userId}
     * 
     * @param userId 用户ID
     * @return 原子操作键名
     * @throws IllegalArgumentException 如果userId为空
     */
    public static String getAtomicKey(String userId) {
        validateUserId(userId);
        return ATOMIC_PREFIX + userId;
    }

    /**
     * 获取临时数据的键名
     * 临时数据键规范：temp:profile:{userId}:{timestamp}
     * 
     * @param userId 用户ID
     * @param timestamp 时间戳
     * @return 临时数据键名
     * @throws IllegalArgumentException 如果参数无效
     */
    public static String getTempKey(String userId, long timestamp) {
        validateUserId(userId);
        if (timestamp <= 0) {
            throw new IllegalArgumentException("时间戳必须大于0");
        }
        return TEMP_PREFIX + userId + ":" + timestamp;
    }

    /**
     * 从profile键名中解析用户ID
     * 
     * @param profileKey profile键名
     * @return 用户ID，如果解析失败返回null
     */
    public static String parseUserIdFromProfileKey(String profileKey) {
        if (profileKey == null || !profileKey.startsWith(PROFILE_PREFIX)) {
            log.warn("无效的profile键名: {}", profileKey);
            return null;
        }
        return profileKey.substring(PROFILE_PREFIX.length());
    }

    /**
     * 从锁键名中解析用户ID
     * 
     * @param lockKey 锁键名
     * @return 用户ID，如果解析失败返回null
     */
    public static String parseUserIdFromLockKey(String lockKey) {
        if (lockKey == null || !lockKey.startsWith(LOCK_PREFIX)) {
            log.warn("无效的锁键名: {}", lockKey);
            return null;
        }
        return lockKey.substring(LOCK_PREFIX.length());
    }

    /**
     * 验证键名是否为有效的profile键
     * 
     * @param key 待验证的键名
     * @return 是否有效
     */
    public static boolean isValidProfileKey(String key) {
        return key != null && key.startsWith(PROFILE_PREFIX) && key.length() > PROFILE_PREFIX.length();
    }

    /**
     * 验证键名是否为有效的锁键
     * 
     * @param key 待验证的键名
     * @return 是否有效
     */
    public static boolean isValidLockKey(String key) {
        return key != null && key.startsWith(LOCK_PREFIX) && key.length() > LOCK_PREFIX.length();
    }

    /**
     * 生成批量操作的键名模式
     * 用于Redis的KEYS或SCAN命令
     * 
     * @return profile键的匹配模式
     */
    public static String getProfileKeyPattern() {
        return PROFILE_PREFIX + "*";
    }

    /**
     * 生成锁键的匹配模式
     * 
     * @return 锁键的匹配模式
     */
    public static String getLockKeyPattern() {
        return LOCK_PREFIX + "*";
    }

    /**
     * 验证用户ID的有效性
     * 
     * @param userId 用户ID
     * @throws IllegalArgumentException 如果用户ID无效
     */
    private static void validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        // 检查用户ID中是否包含特殊字符
        if (userId.contains(":") || userId.contains("*") || userId.contains("?")) {
            throw new IllegalArgumentException("用户ID不能包含特殊字符: : * ?");
        }
    }

    /**
     * 键名信息封装类
     */
    public static class KeyInfo {
        private final String key;
        private final String userId;
        private final KeyType type;
        
        public KeyInfo(String key, String userId, KeyType type) {
            this.key = key;
            this.userId = userId;
            this.type = type;
        }
        
        public String getKey() { return key; }
        public String getUserId() { return userId; }
        public KeyType getType() { return type; }
        
        @Override
        public String toString() {
            return String.format("KeyInfo{key='%s', userId='%s', type=%s}", key, userId, type);
        }
    }
    
    /**
     * 键名类型枚举
     */
    public enum KeyType {
        PROFILE("profile"),
        LOCK("lock"),
        ATOMIC("atomic"),
        TEMP("temp"),
        UNKNOWN("unknown");
        
        private final String description;
        
        KeyType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
        
        @Override
        public String toString() { return description; }
    }
    
    /**
     * 解析键名信息
     * 
     * @param key 键名
     * @return 键名信息
     */
    public static KeyInfo parseKeyInfo(String key) {
        if (key == null) {
            return new KeyInfo(key, null, KeyType.UNKNOWN);
        }
        
        if (key.startsWith(PROFILE_PREFIX)) {
            String userId = parseUserIdFromProfileKey(key);
            return new KeyInfo(key, userId, KeyType.PROFILE);
        } else if (key.startsWith(LOCK_PREFIX)) {
            String userId = parseUserIdFromLockKey(key);
            return new KeyInfo(key, userId, KeyType.LOCK);
        } else if (key.startsWith(ATOMIC_PREFIX)) {
            String userId = key.substring(ATOMIC_PREFIX.length());
            return new KeyInfo(key, userId, KeyType.ATOMIC);
        } else if (key.startsWith(TEMP_PREFIX)) {
            String remaining = key.substring(TEMP_PREFIX.length());
            int colonIndex = remaining.indexOf(':');
            String userId = colonIndex > 0 ? remaining.substring(0, colonIndex) : remaining;
            return new KeyInfo(key, userId, KeyType.TEMP);
        }
        
        return new KeyInfo(key, null, KeyType.UNKNOWN);
    }
}