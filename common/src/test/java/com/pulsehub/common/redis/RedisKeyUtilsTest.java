package com.pulsehub.common.redis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisKeyUtils 测试类
 */
class RedisKeyUtilsTest {

    @Test
    void testGetProfileKey() {
        assertEquals("profile:user:test123", RedisKeyUtils.getProfileKey("test123"));
        assertEquals("profile:user:user456", RedisKeyUtils.getProfileKey("user456"));
    }

    @Test
    void testGetLockKey() {
        assertEquals("lock:profile:test123", RedisKeyUtils.getLockKey("test123"));
        assertEquals("lock:profile:user456", RedisKeyUtils.getLockKey("user456"));
    }

    @Test
    void testGetAtomicKey() {
        assertEquals("atomic:profile:user:test123", RedisKeyUtils.getAtomicKey("test123"));
    }

    @Test
    void testParseUserIdFromProfileKey() {
        assertEquals("test123", RedisKeyUtils.parseUserIdFromProfileKey("profile:user:test123"));
        assertNull(RedisKeyUtils.parseUserIdFromProfileKey("invalid:key"));
        assertNull(RedisKeyUtils.parseUserIdFromProfileKey(null));
    }

    @Test
    void testValidateUserId() {
        // 正常情况下不抛异常
        assertDoesNotThrow(() -> RedisKeyUtils.getProfileKey("validUser"));
        
        // 异常情况
        assertThrows(IllegalArgumentException.class, () -> RedisKeyUtils.getProfileKey(null));
        assertThrows(IllegalArgumentException.class, () -> RedisKeyUtils.getProfileKey(""));
        assertThrows(IllegalArgumentException.class, () -> RedisKeyUtils.getProfileKey("user:invalid"));
    }

    @Test
    void testKeyValidation() {
        assertTrue(RedisKeyUtils.isValidProfileKey("profile:user:test123"));
        assertFalse(RedisKeyUtils.isValidProfileKey("invalid:key"));
        assertFalse(RedisKeyUtils.isValidProfileKey(null));
        
        assertTrue(RedisKeyUtils.isValidLockKey("lock:profile:test123"));
        assertFalse(RedisKeyUtils.isValidLockKey("invalid:key"));
    }

    @Test
    void testParseKeyInfo() {
        RedisKeyUtils.KeyInfo info = RedisKeyUtils.parseKeyInfo("profile:user:test123");
        assertEquals("test123", info.getUserId());
        assertEquals(RedisKeyUtils.KeyType.PROFILE, info.getType());
        
        RedisKeyUtils.KeyInfo unknownInfo = RedisKeyUtils.parseKeyInfo("unknown:key");
        assertEquals(RedisKeyUtils.KeyType.UNKNOWN, unknownInfo.getType());
    }

    @Test
    void testKeyPatterns() {
        assertEquals("profile:user:*", RedisKeyUtils.getProfileKeyPattern());
        assertEquals("lock:profile:*", RedisKeyUtils.getLockKeyPattern());
    }
}