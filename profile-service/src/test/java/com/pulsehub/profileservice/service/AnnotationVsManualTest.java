package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Slf4j
@SpringBootTest(classes = {AnnotationVsManualTest.TestCacheConfig.class, ProfileService.class, ManualCacheExample.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AnnotationVsManualTest {

    @Configuration
    @EnableCaching
    static class TestCacheConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("user-profiles");
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
        cacheManager.getCacheNames().forEach(name ->
                cacheManager.getCache(name).clear());

        testProfile = UserProfile.builder()
                .userId("test-user")
                .email("test@example.com")
                .fullName("Test User")
                .build();

        when(userProfileRepository.findById("test-user"))
                .thenReturn(Optional.of(testProfile));

        when(redisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
    }

    @Test
    void testAnnotationBasedCaching() {
        log.info("\n🎭 === 测试注解版本的缓存行为 ===");

        String userId = "test-user";

        log.info("📞 第一次调用注解版本...");
        Optional<UserProfile> firstResult = profileService.getProfileByUserId(userId);

        log.info("📞 第二次调用注解版本...");
        Optional<UserProfile> secondResult = profileService.getProfileByUserId(userId);

        verify(userProfileRepository, times(1)).findById(userId);

        assertThat(firstResult).isPresent().isEqualTo(secondResult);
        assertThat(firstResult.get()).isEqualTo(testProfile);

        log.info("✅ 注解版本缓存行为验证通过");
    }

    @Test
    void testManualCaching() {
        log.info("\n🔧 === 测试手动版本的缓存行为 ===");

        String userId = "test-user";
        String expectedKey = "user-profiles::" + userId;

        when(redisTemplate.opsForValue().get(expectedKey))
                .thenReturn(null)
                .thenReturn(testProfile);

        log.info("📞 第一次调用手动版本...");
        Optional<UserProfile> firstResult = manualCacheExample.getProfileManual(userId);

        log.info("📞 第二次调用手动版本...");
        Optional<UserProfile> secondResult = manualCacheExample.getProfileManual(userId);

        verify(redisTemplate.opsForValue(), times(2)).get(expectedKey);
        verify(redisTemplate.opsForValue(), times(1)).set(eq(expectedKey), eq(testProfile), any());

        verify(userProfileRepository, times(1)).findById(userId);

        assertThat(firstResult).isPresent().contains(testProfile);
        assertThat(secondResult).isPresent().contains(testProfile);

        log.info("✅ 手动版本缓存行为验证通过");
    }

    @Test
    void testAnnotationVsManualEquivalence() {
        log.info("\n⚖️ === 注解版本 vs 手动版本等价性测试 ===");

        String userId1 = "test-user-1";
        String userId2 = "test-user-2";

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

        when(redisTemplate.opsForValue().get("user-profiles::" + userId2))
            .thenReturn(null)
            .thenReturn(profile2);

        log.info("📱 注解版本查询用户1...");
        Optional<UserProfile> annotationResult1 = profileService.getProfileByUserId(userId1);
        Optional<UserProfile> annotationResult2 = profileService.getProfileByUserId(userId1);

        log.info("🔧 手动版本查询用户2...");
        Optional<UserProfile> manualResult1 = manualCacheExample.getProfileManual(userId2);
        Optional<UserProfile> manualResult2 = manualCacheExample.getProfileManual(userId2);

        assertThat(annotationResult1).isPresent().contains(profile1);
        assertThat(annotationResult2).isPresent().contains(profile1);
        assertThat(manualResult1).isPresent().contains(profile2);
        assertThat(manualResult2).isPresent().contains(profile2);

        verify(userProfileRepository, times(1)).findById(userId1);
        verify(userProfileRepository, times(1)).findById(userId2);

        assertThat(annotationResult1).isEqualTo(annotationResult2);

        log.info("✅ 注解版本和手动版本行为完全等价");
    }

    @Test
    void testCacheNamespaceIsolation() {
        log.info("\n🏠 === 缓存命名空间隔离测试 ===");

        String userId = "test-user";

        Optional<UserProfile> result1 = profileService.getProfileByUserId(userId);
        Optional<UserProfile> result2 = profileService.getProfileByUserId(userId);

        verify(userProfileRepository, times(1)).findById(userId);
        assertThat(result1).isEqualTo(result2);

        assertThat(cacheManager.getCacheNames()).contains("user-profiles");
        assertThat(cacheManager.getCache("user-profiles").get(userId)).isNotNull();

        log.info("✅ 缓存命名空间隔离正常工作");
    }

    @Test
    void testCachePerformanceImprovement() {
        log.info("\n⚡ === 缓存性能提升验证测试 ===");

        String userId = "perf-test-user";
        UserProfile perfTestProfile = UserProfile.builder()
                .userId(userId)
                .email("perf@test.com")
                .build();

        when(userProfileRepository.findById(userId)).thenReturn(Optional.of(perfTestProfile));

        long startTime1 = System.nanoTime();
        Optional<UserProfile> firstCall = profileService.getProfileByUserId(userId);
        long firstCallTime = System.nanoTime() - startTime1;

        long startTime2 = System.nanoTime();
        Optional<UserProfile> secondCall = profileService.getProfileByUserId(userId);
        long secondCallTime = System.nanoTime() - startTime2;

        assertThat(firstCall).isEqualTo(secondCall);

        log.info("📊 性能对比结果:");
        log.info("   第一次调用（含数据库）: {} ns", firstCallTime);
        log.info("   第二次调用（纯缓存）: {} ns", secondCallTime);
        log.info("   性能提升比例: {:.2f}x", (double) firstCallTime / secondCallTime);

        assertThat(secondCallTime).isLessThan(firstCallTime);

        log.info("✅ 缓存性能提升验证通过");
    }
}
 