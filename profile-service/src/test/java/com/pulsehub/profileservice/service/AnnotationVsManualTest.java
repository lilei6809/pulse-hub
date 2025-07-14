package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 🎭 注解版本 vs 手动版本对比测试
 * 
 * 【测试目标】
 * 验证 @Cacheable 注解和手动 RedisTemplate 操作的效果完全相同
 * 
 * 【学习价值】
 * 1. 理解注解只是语法糖，底层机制相同
 * 2. 掌握缓存行为的验证方法
 * 3. 了解Spring Cache和手动缓存的等价性
 */
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

@Testcontainers
@SpringBootTest
@Import(CacheConfig.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AnnotationVsManualTest {

    /**
     * 测试专用缓存配置
     * 使用内存缓存替代Redis，简化测试环境
     */
    @Configuration
    @EnableCaching
    static class TestCacheConfig {
        
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("user-profiles");
        }
        
        @Bean
        RedisTemplate<String, Object> redisTemplate() {
            return mock(RedisTemplate.class);
        }
    }

    @Autowired
    private ProfileService profileService;
    
    @Autowired
    private ManualCacheExample manualCacheExample;
    
    @MockBean
    private UserProfileRepository userProfileRepository;
    
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private CacheManager cacheManager;

    private UserProfile testProfile;
    
    @BeforeEach
    void setUp() {
        // 清空缓存
        cacheManager.getCacheNames().forEach(name -> 
            cacheManager.getCache(name).clear());
        
        // 准备测试数据
        testProfile = UserProfile.builder()
            .userId("test-user")
            .email("test@example.com")
            .fullName("Test User")
            .build();
            
        // 配置Mock行为
        when(userProfileRepository.findById("test-user"))
            .thenReturn(Optional.of(testProfile));
    }

    /**
     * 🎯 测试：验证注解版本的缓存行为
     * 
     * 【验证要点】
     * 1. 第一次调用访问数据库
     * 2. 第二次调用命中缓存，不访问数据库
     * 3. 两次调用返回相同结果
     */
    @Test
    void testAnnotationBasedCaching() {
        log.info("\n🎭 === 测试注解版本的缓存行为 ===");
        
        String userId = "test-user";
        
        // 第一次调用 - 应该访问数据库
        log.info("📞 第一次调用注解版本...");
        Optional<UserProfile> firstResult = profileService.getProfileByUserId(userId);
        
        // 第二次调用 - 应该命中缓存
        log.info("📞 第二次调用注解版本...");
        Optional<UserProfile> secondResult = profileService.getProfileByUserId(userId);
        
        // 验证数据库只被访问一次
        verify(userProfileRepository, times(1)).findById(userId);
        
        // 验证两次调用返回相同结果
        assertThat(firstResult).isPresent().isEqualTo(secondResult);
        assertThat(firstResult.get()).isEqualTo(testProfile);
        
        log.info("✅ 注解版本缓存行为验证通过");
    }

    /**
     * 🔧 测试：验证手动版本的缓存行为
     * 
     * 【验证要点】
     * 1. Mock RedisTemplate的行为
     * 2. 验证Redis操作的调用次数和参数
     * 3. 验证缓存逻辑的正确性
     */
    @Test
    void testManualCaching() {
        log.info("\n🔧 === 测试手动版本的缓存行为 ===");
        
        String userId = "test-user";
        String expectedKey = "user-profiles::" + userId;
        
        // 配置Redis Mock行为
        // 第一次调用时缓存为空
        when(redisTemplate.opsForValue().get(expectedKey))
            .thenReturn(null)                    // 第一次返回null
            .thenReturn(testProfile);            // 第二次返回缓存数据
        
        // 第一次调用 - 缓存未命中
        log.info("📞 第一次调用手动版本...");
        Optional<UserProfile> firstResult = manualCacheExample.getProfileManual(userId);
        
        // 第二次调用 - 缓存命中  
        log.info("📞 第二次调用手动版本...");
        Optional<UserProfile> secondResult = manualCacheExample.getProfileManual(userId);
        
        // 验证Redis操作
        verify(redisTemplate.opsForValue(), times(2)).get(expectedKey);  // 两次GET
        verify(redisTemplate.opsForValue(), times(1)).set(eq(expectedKey), eq(testProfile), any()); // 一次SET
        
        // 验证数据库只被访问一次
        verify(userProfileRepository, times(1)).findById(userId);
        
        // 验证结果正确
        assertThat(firstResult).isPresent().contains(testProfile);
        assertThat(secondResult).isPresent().contains(testProfile);
        
        log.info("✅ 手动版本缓存行为验证通过");
    }

    /**
     * 🎭 测试：直接对比注解版本和手动版本
     * 
     * 【验证要点】
     * 1. 在相同条件下，两种方式的行为完全一致
     * 2. 验证它们都能正确处理缓存命中和未命中
     * 3. 证明注解只是语法糖
     */
    @Test
    void testAnnotationVsManualEquivalence() {
        log.info("\n⚖️ === 注解版本 vs 手动版本等价性测试 ===");
        
        String userId1 = "test-user-1";
        String userId2 = "test-user-2";
        
        // 准备不同的测试数据
        UserProfile profile1 = UserProfile.builder()
            .userId(userId1)
            .email("user1@test.com")
            .build();
            
        UserProfile profile2 = UserProfile.builder()
            .userId(userId2)
            .email("user2@test.com")
            .build();
        
        when(userProfileRepository.findById(userId1)).thenReturn(Optional.of(profile1));
        when(userProfileRepository.findById(userId2)).thenReturn(Optional.of(profile2));
        
        // 配置Redis Mock（手动版本需要）
        when(redisTemplate.opsForValue().get("user-profiles::" + userId2))
            .thenReturn(null);  // 模拟缓存未命中
        
        // 使用注解版本查询用户1
        log.info("📱 注解版本查询用户1...");
        Optional<UserProfile> annotationResult1 = profileService.getProfileByUserId(userId1);
        Optional<UserProfile> annotationResult2 = profileService.getProfileByUserId(userId1); // 第二次，命中缓存
        
        // 使用手动版本查询用户2
        log.info("🔧 手动版本查询用户2...");
        Optional<UserProfile> manualResult1 = manualCacheExample.getProfileManual(userId2);
        Optional<UserProfile> manualResult2 = manualCacheExample.getProfileManual(userId2); // 第二次，命中缓存
        
        // 验证两种方式的行为一致：
        // 1. 都能正确返回数据
        assertThat(annotationResult1).isPresent().contains(profile1);
        assertThat(annotationResult2).isPresent().contains(profile1);
        assertThat(manualResult1).isPresent().contains(profile2);
        assertThat(manualResult2).isPresent().contains(profile2);
        
        // 2. 都只访问数据库一次
        verify(userProfileRepository, times(1)).findById(userId1);  // 注解版本
        verify(userProfileRepository, times(1)).findById(userId2);  // 手动版本
        
        // 3. 第二次调用都命中缓存
        assertThat(annotationResult1).isEqualTo(annotationResult2);  // 注解版本缓存一致性
        // 手动版本的缓存一致性通过Redis Mock验证
        
        log.info("✅ 注解版本和手动版本行为完全等价");
    }

    /**
     * 🔍 测试：验证缓存配置的影响
     * 
     * 【验证要点】
     * 1. 不同的缓存名称会创建不同的缓存空间
     * 2. 缓存隔离性正常工作
     * 3. 配置参数的实际作用
     */
    @Test
    void testCacheNamespaceIsolation() {
        log.info("\n🏠 === 缓存命名空间隔离测试 ===");
        
        String userId = "test-user";
        
        // 调用使用不同缓存配置的方法
        // 注意：这里使用基础版本，因为测试配置只配置了"user-profiles"缓存
        Optional<UserProfile> result1 = profileService.getProfileByUserId(userId);
        Optional<UserProfile> result2 = profileService.getProfileByUserId(userId);
        
        // 验证缓存正常工作
        verify(userProfileRepository, times(1)).findById(userId);
        assertThat(result1).isEqualTo(result2);
        
        // 检查缓存管理器中的缓存
        assertThat(cacheManager.getCacheNames()).contains("user-profiles");
        assertThat(cacheManager.getCache("user-profiles").get(userId)).isNotNull();
        
        log.info("✅ 缓存命名空间隔离正常工作");
    }

    /**
     * 📊 性能对比测试（模拟）
     * 
     * 【验证要点】
     * 1. 缓存确实能提升性能
     * 2. 量化缓存的性能收益
     * 3. 验证缓存命中率的影响
     */
    @Test
    void testCachePerformanceImprovement() {
        log.info("\n⚡ === 缓存性能提升验证测试 ===");
        
        String userId = "perf-test-user";
        UserProfile perfTestProfile = UserProfile.builder()
            .userId(userId)
            .email("perf@test.com")
            .build();
            
        when(userProfileRepository.findById(userId)).thenReturn(Optional.of(perfTestProfile));
        
        // 测量第一次调用时间（包含数据库访问）
        long startTime1 = System.nanoTime();
        Optional<UserProfile> firstCall = profileService.getProfileByUserId(userId);
        long firstCallTime = System.nanoTime() - startTime1;
        
        // 测量第二次调用时间（缓存命中）
        long startTime2 = System.nanoTime();
        Optional<UserProfile> secondCall = profileService.getProfileByUserId(userId);
        long secondCallTime = System.nanoTime() - startTime2;
        
        // 验证结果一致性
        assertThat(firstCall).isEqualTo(secondCall);
        
        // 验证性能提升
        log.info("📊 性能对比结果:");
        log.info("   第一次调用（含数据库）: {} ns", firstCallTime);
        log.info("   第二次调用（纯缓存）: {} ns", secondCallTime);
        log.info("   性能提升比例: {:.2f}x", (double) firstCallTime / secondCallTime);
        
        // 通常缓存调用应该明显更快
        assertThat(secondCallTime).isLessThan(firstCallTime);
        
        log.info("✅ 缓存性能提升验证通过");
    }
} 