package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.DynamicUserProfileSerializer;
import com.pulsehub.profileservice.repository.StaticUserProfileRepository;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DynamicProfileService 简化测试
 * 
 * 使用 Mock 对象避免复杂的 Spring 上下文配置
 */
class DynamicProfileServiceSimpleTest {

    private DynamicProfileService dynamicProfileService;
    private RedisTemplate<String, Object> redisTemplate;
    private StaticUserProfileRepository staticProfileRepository;
    private ApplicationEventPublisher eventPublisher;
    private DynamicUserProfileSerializer serializer;
    private ValueOperations<String, Object> valueOperations;
    private ZSetOperations<String, Object> zSetOperations;

    private static final String TEST_USER_ID = "test-user-123";

    @BeforeEach
    void setUp() {
        // 创建 Mock 对象
        redisTemplate = mock(RedisTemplate.class);
        staticProfileRepository = mock(StaticUserProfileRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        serializer = new DynamicUserProfileSerializer(); // 使用真实的序列化器
        valueOperations = mock(ValueOperations.class);
        zSetOperations = mock(ZSetOperations.class);

        // 设置 Mock 行为
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        // 创建被测试的服务
        dynamicProfileService = new DynamicProfileService(
            redisTemplate, 
            staticProfileRepository, 
            eventPublisher, 
            serializer
        );
    }

    @Test
    @DisplayName("简化测试：createProfile 应该正确处理用户画像创建")
    void createProfile_ShouldHandleProfileCreation() {
        // Given
        DynamicUserProfile profile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .pageViewCount(25L)
                .deviceClassification(DeviceClass.MOBILE)
                .build();

        // 模拟 Redis 操作
        when(valueOperations.increment("dynamic_profile_count")).thenReturn(1L);

        // When
        DynamicUserProfile result = dynamicProfileService.createProfile(profile);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getPageViewCount()).isEqualTo(25L);
        assertThat(result.getDeviceClassification()).isEqualTo(DeviceClass.MOBILE);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getLastActiveAt()).isNotNull();

        // 验证 Redis 操作被调用 (注意：set 调用包含 TTL 参数)
        verify(valueOperations).set(eq("dynamic_profile:" + TEST_USER_ID), anyString(), any());
        verify(valueOperations).increment("dynamic_profile_count");
        verify(zSetOperations).add(eq("active_users:recent"), eq(TEST_USER_ID), anyDouble());
        verify(zSetOperations).add(eq("pageview_index"), eq(TEST_USER_ID), eq(25.0));
    }

    @Test
    @DisplayName("简化测试：getProfile 应该正确检索用户画像")
    void getProfile_ShouldRetrieveProfile() {
        // Given
        DynamicUserProfile expectedProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .pageViewCount(50L)
                .deviceClassification(DeviceClass.DESKTOP)
                .version(1L)
                .build();

        String serializedProfile = serializer.serialize(expectedProfile);
        when(valueOperations.get("dynamic_profile:" + TEST_USER_ID)).thenReturn(serializedProfile);

        // When
        Optional<DynamicUserProfile> result = dynamicProfileService.getProfile(TEST_USER_ID);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(result.get().getPageViewCount()).isEqualTo(50L);
        assertThat(result.get().getDeviceClassification()).isEqualTo(DeviceClass.DESKTOP);
        
        verify(valueOperations).get("dynamic_profile:" + TEST_USER_ID);
    }

    @Test
    @DisplayName("简化测试：getProfile 不存在的用户应该返回空")
    void getProfile_NonExistentUser_ShouldReturnEmpty() {
        // Given
        when(valueOperations.get("dynamic_profile:non-existent")).thenReturn(null);

        // When
        Optional<DynamicUserProfile> result = dynamicProfileService.getProfile("non-existent");

        // Then
        assertThat(result).isEmpty();
        verify(valueOperations).get("dynamic_profile:non-existent");
    }

    @Test
    @DisplayName("简化测试：createProfile 无效输入应该抛出异常")
    void createProfile_InvalidInput_ShouldThrowException() {
        // Given
        DynamicUserProfile invalidProfile = DynamicUserProfile.builder()
                .userId(null)
                .pageViewCount(10L)
                .build();

        // When & Then
        assertThatThrownBy(() -> dynamicProfileService.createProfile(invalidProfile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户ID不能为空");

        // 验证没有执行任何 Redis 操作
        verifyNoInteractions(valueOperations);
        verifyNoInteractions(zSetOperations);
    }

    @Test
    @DisplayName("简化测试：createProfile 应该正确设置默认值")
    void createProfile_ShouldSetDefaultValues() {
        // Given
        DynamicUserProfile minimalProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .build();

        when(valueOperations.increment("dynamic_profile_count")).thenReturn(1L);

        // When
        DynamicUserProfile result = dynamicProfileService.createProfile(minimalProfile);

        // Then
        assertThat(result.getPageViewCount()).isEqualTo(0L);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getLastActiveAt()).isNotNull();
        assertThat(result.getRecentDeviceTypes()).isNotNull().isEmpty();

        // 验证页面浏览数索引使用默认值 0
        verify(zSetOperations).add(eq("pageview_index"), eq(TEST_USER_ID), eq(0.0));
    }
}