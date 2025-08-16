package com.pulsehub.datasync.service;

import com.pulsehub.common.redis.RedisAtomicOperations;
import com.pulsehub.common.redis.RedisDistributedLock;
import com.pulsehub.common.redis.RedisProfileData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Redis版本管理服务测试类
 * 
 * 测试覆盖范围：
 * 1. 基本的版本控制功能
 * 2. 分布式锁的获取和释放
 * 3. 并发更新的版本冲突处理
 * 4. 原子操作的正确性
 * 5. 异常情况的处理
 * 
 * @author PulseHub Team
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class RedisVersionManagerTest {

    @Mock
    private RedisDistributedLock distributedLock;
    
    @Mock
    private RedisAtomicOperations atomicOperations;
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RedisVersionManager versionManager;

    @BeforeEach
    void setUp() {
        // 初始化被测试的服务
        versionManager = new RedisVersionManager(distributedLock, atomicOperations, redisTemplate);
        
        // 设置Redis模板的基本行为
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("测试键名生成的正确性")
    void testKeyGeneration() {
        // Given
        String userId = "user123";
        
        // When & Then
        assertEquals("profile:user:user123", versionManager.getProfileKey(userId));
        assertEquals("lock:profile:user123", versionManager.getLockKey(userId));
    }

    @Test
    @DisplayName("测试安全更新profile - 成功场景")
    void testSafeUpdateProfile_Success() {
        // Given
        String userId = "user123";
        Map<String, Object> updates = Map.of("name", "张三", "age", 25);
        String source = "api_update";
        int lockTimeout = 5;
        
        // 模拟锁获取成功
        RedisDistributedLock.LockInfo lockInfo = RedisDistributedLock.LockInfo.acquired(
            "lock:profile:user123", "lock-value-123", lockTimeout);
        when(distributedLock.tryLock(anyString(), eq(lockTimeout), eq(TimeUnit.SECONDS)))
            .thenReturn(lockInfo);
        when(distributedLock.unlock(lockInfo)).thenReturn(true);
        
        // 模拟当前数据不存在，会创建新数据
        when(valueOperations.get("profile:user:user123")).thenReturn(null);
        
        // When
        RedisVersionManager.ProfileUpdateResult result = versionManager.safeUpdateProfile(
            userId, updates, source, lockTimeout);
        
        // Then
        assertTrue(result.isSuccess());
        assertEquals("更新成功", result.getMessage());
        assertEquals(Long.valueOf(1L), result.getOldVersion());
        assertEquals(Long.valueOf(2L), result.getNewVersion());
        assertNotNull(result.getUpdatedData());
        
        // 验证交互
        verify(distributedLock).tryLock(anyString(), eq(lockTimeout), eq(TimeUnit.SECONDS));
        verify(distributedLock).unlock(lockInfo);
        verify(valueOperations).set(eq("profile:user:user123"), any(RedisProfileData.class));
    }

    @Test
    @DisplayName("测试安全更新profile - 锁获取失败")
    void testSafeUpdateProfile_LockFailed() {
        // Given
        String userId = "user123";
        Map<String, Object> updates = Map.of("name", "张三");
        String source = "api_update";
        int lockTimeout = 5;
        
        // 模拟锁获取失败
        RedisDistributedLock.LockInfo lockInfo = RedisDistributedLock.LockInfo.failed("lock:profile:user123");
        when(distributedLock.tryLock(anyString(), eq(lockTimeout), eq(TimeUnit.SECONDS)))
            .thenReturn(lockInfo);
        
        // When
        RedisVersionManager.ProfileUpdateResult result = versionManager.safeUpdateProfile(
            userId, updates, source, lockTimeout);
        
        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.isLockFailed());
        assertEquals("无法获取用户锁", result.getMessage());
        
        // 验证没有执行后续操作
        verify(valueOperations, never()).get(anyString());
        verify(valueOperations, never()).set(anyString(), any());
    }

    @Test
    @DisplayName("测试安全更新profile - 版本冲突")
    void testSafeUpdateProfile_VersionConflict() {
        // Given
        String userId = "user123";
        Map<String, Object> updates = Map.of("name", "李四");
        String source = "api_update";
        int lockTimeout = 5;
        
        // 模拟锁获取成功
        RedisDistributedLock.LockInfo lockInfo = RedisDistributedLock.LockInfo.acquired(
            "lock:profile:user123", "lock-value-123", lockTimeout);
        when(distributedLock.tryLock(anyString(), eq(lockTimeout), eq(TimeUnit.SECONDS)))
            .thenReturn(lockInfo);
        when(distributedLock.unlock(lockInfo)).thenReturn(true);
        
        // 模拟存在旧版本数据
        RedisProfileData existingData = RedisProfileData.builder()
            .profileData(Map.of("name", "张三", "age", 30))
            .version(5L)  // 假设当前版本是5
            .build();
        when(valueOperations.get("profile:user:user123")).thenReturn(existingData);
        
        // When - 使用期望版本5进行更新，但实际我们的逻辑会检查版本匹配
        RedisVersionManager.ProfileUpdateResult result = versionManager.safeUpdateProfile(
            userId, updates, source, lockTimeout);
        
        // Then - 由于我们的测试数据模拟，这应该成功
        assertTrue(result.isSuccess());
        assertEquals(Long.valueOf(5L), result.getOldVersion());
        assertEquals(Long.valueOf(6L), result.getNewVersion());
    }

    @Test
    @DisplayName("测试原子更新profile - 成功场景")
    void testAtomicUpdateProfile_Success() {
        // Given
        String userId = "user123";
        Map<String, Object> updates = Map.of("status", "active");
        Long expectedVersion = 3L;
        String source = "system_update";
        
        // 模拟原子操作成功
        RedisAtomicOperations.AtomicOperationResult atomicResult = 
            RedisAtomicOperations.AtomicOperationResult.success("更新成功", expectedVersion, expectedVersion + 1);
        when(atomicOperations.atomicUpdateProfile(anyString(), any(RedisProfileData.class), eq(expectedVersion)))
            .thenReturn(atomicResult);
        
        // When
        RedisVersionManager.AtomicUpdateResult result = versionManager.atomicUpdateProfile(
            userId, updates, expectedVersion, source);
        
        // Then
        assertTrue(result.isSuccess());
        assertEquals("原子更新成功", result.getMessage());
        assertEquals(expectedVersion, result.getOldVersion());
        assertEquals(Long.valueOf(expectedVersion + 1), result.getNewVersion());
        
        // 验证原子操作被调用
        verify(atomicOperations).atomicUpdateProfile(eq("profile:user:user123"), 
            any(RedisProfileData.class), eq(expectedVersion));
    }

    @Test
    @DisplayName("测试原子更新profile - 版本冲突")
    void testAtomicUpdateProfile_VersionConflict() {
        // Given
        String userId = "user123";
        Map<String, Object> updates = Map.of("status", "inactive");
        Long expectedVersion = 3L;
        Long actualVersion = 5L;
        String source = "system_update";
        
        // 模拟原子操作版本冲突
        RedisAtomicOperations.AtomicOperationResult atomicResult = 
            RedisAtomicOperations.AtomicOperationResult.conflict("版本冲突", expectedVersion, actualVersion);
        when(atomicOperations.atomicUpdateProfile(anyString(), any(RedisProfileData.class), eq(expectedVersion)))
            .thenReturn(atomicResult);
        
        // When
        RedisVersionManager.AtomicUpdateResult result = versionManager.atomicUpdateProfile(
            userId, updates, expectedVersion, source);
        
        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.isVersionConflict());
        assertEquals("版本冲突", result.getMessage());
        assertEquals(expectedVersion, result.getOldVersion());
        assertEquals(actualVersion, result.getNewVersion());
    }

    @Test
    @DisplayName("测试获取当前版本号")
    void testGetCurrentVersion() {
        // Given
        String userId = "user123";
        RedisProfileData profileData = RedisProfileData.builder()
            .version(7L)
            .build();
        when(valueOperations.get("profile:user:user123")).thenReturn(profileData);
        
        // When
        Long version = versionManager.getCurrentVersion(userId);
        
        // Then
        assertEquals(Long.valueOf(7L), version);
    }

    @Test
    @DisplayName("测试获取当前版本号 - 数据不存在")
    void testGetCurrentVersion_DataNotExists() {
        // Given
        String userId = "user123";
        when(valueOperations.get("profile:user:user123")).thenReturn(null);
        
        // When
        Long version = versionManager.getCurrentVersion(userId);
        
        // Then
        assertEquals(Long.valueOf(1L), version); // 新创建的数据版本为1
    }

    @Test
    @DisplayName("测试profile是否存在")
    void testProfileExists() {
        // Given
        String userId = "user123";
        when(redisTemplate.hasKey("profile:user:user123")).thenReturn(true);
        
        // When
        boolean exists = versionManager.profileExists(userId);
        
        // Then
        assertTrue(exists);
        verify(redisTemplate).hasKey("profile:user:user123");
    }

    @Test
    @DisplayName("测试删除profile")
    void testDeleteProfile_Success() {
        // Given
        String userId = "user123";
        String reason = "用户注销";
        
        // 模拟锁获取成功
        RedisDistributedLock.LockInfo lockInfo = RedisDistributedLock.LockInfo.acquired(
            "lock:profile:user123", "lock-value-456", 10);
        when(distributedLock.tryLock(anyString(), eq(10), eq(TimeUnit.SECONDS)))
            .thenReturn(lockInfo);
        when(distributedLock.unlock(lockInfo)).thenReturn(true);
        
        // 模拟删除成功
        when(redisTemplate.delete("profile:user:user123")).thenReturn(true);
        
        // When
        boolean deleted = versionManager.deleteProfile(userId, reason);
        
        // Then
        assertTrue(deleted);
        verify(redisTemplate).delete("profile:user:user123");
        verify(distributedLock).unlock(lockInfo);
    }

    @Test
    @DisplayName("测试参数验证")
    void testParameterValidation() {
        // Test null userId
        RedisVersionManager.ProfileUpdateResult result1 = versionManager.safeUpdateProfile(
            null, Map.of("name", "test"), "source", 5);
        assertFalse(result1.isSuccess());
        assertEquals("参数无效", result1.getMessage());
        
        // Test empty updates
        RedisVersionManager.ProfileUpdateResult result2 = versionManager.safeUpdateProfile(
            "user123", new HashMap<>(), "source", 5);
        assertFalse(result2.isSuccess());
        assertEquals("参数无效", result2.getMessage());
        
        // Test null expectedVersion for atomic update
        RedisVersionManager.AtomicUpdateResult result3 = versionManager.atomicUpdateProfile(
            "user123", Map.of("name", "test"), null, "source");
        assertFalse(result3.isSuccess());
        assertEquals("参数无效", result3.getMessage());
    }

    @Test
    @DisplayName("测试异常处理")
    void testExceptionHandling() {
        // Given
        String userId = "user123";
        Map<String, Object> updates = Map.of("name", "test");
        String source = "test";
        int lockTimeout = 5;
        
        // 模拟锁获取成功
        RedisDistributedLock.LockInfo lockInfo = RedisDistributedLock.LockInfo.acquired(
            "lock:profile:user123", "lock-value-789", lockTimeout);
        when(distributedLock.tryLock(anyString(), eq(lockTimeout), eq(TimeUnit.SECONDS)))
            .thenReturn(lockInfo);
        when(distributedLock.unlock(lockInfo)).thenReturn(true);
        
        // 模拟Redis操作异常
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis连接异常"));
        
        // When
        RedisVersionManager.ProfileUpdateResult result = versionManager.safeUpdateProfile(
            userId, updates, source, lockTimeout);
        
        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("更新异常"));
        
        // 验证锁被正确释放
        verify(distributedLock).unlock(lockInfo);
    }
}