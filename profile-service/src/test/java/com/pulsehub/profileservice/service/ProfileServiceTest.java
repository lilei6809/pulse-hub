package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = {ProfileServiceTest.CachingTestConfig.class, ProfileService.class}
)
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    RedisAutoConfiguration.class
})
class ProfileServiceTest {

    @TestConfiguration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("user-profiles");
        }
    }

    @Autowired
    private ProfileService profileService;

    @MockBean
    private UserProfileRepository userProfileRepository;

    @Autowired
    private CacheManager cacheManager;

    private Cache userProfileCache;

    @BeforeEach
    void setUp() {
        userProfileCache = cacheManager.getCache("user-profiles");
        if (userProfileCache != null) {
            userProfileCache.clear();
        }
    }

    @Test
    void whenGetProfileIsCalledTwice_thenDatabaseShouldBeHitOnlyOnce() {
        // GIVEN
        final String userId = "user-123";
        final UserProfile expectedProfile = UserProfile.builder()
                .userId(userId)
                .email("test@example.com")
                .fullName("Test User")
                .build();

        when(userProfileRepository.findById(userId)).thenReturn(Optional.of(expectedProfile));

        // WHEN & THEN (第一次调用)
        System.out.println("--- 第一次调用 service.getProfileByUserId ---");
        Optional<UserProfile> firstCallResult = profileService.getProfileByUserId(userId);

        assertThat(firstCallResult).isPresent().contains(expectedProfile);
        verify(userProfileRepository, times(1)).findById(userId);
        assertThat(userProfileCache.get(userId, UserProfile.class)).isEqualTo(expectedProfile);
        System.out.println("缓存内容: " + userProfileCache.get(userId, UserProfile.class));

        // WHEN & THEN (第二次调用)
        System.out.println("\n--- 第二次调用 service.getProfileByUserId ---");
        Optional<UserProfile> secondCallResult = profileService.getProfileByUserId(userId);

        assertThat(secondCallResult).isPresent().contains(expectedProfile);
        verifyNoMoreInteractions(userProfileRepository);
        System.out.println("验证成功：数据库没有被再次访问！");
    }
}