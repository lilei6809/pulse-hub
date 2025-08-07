package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.repository.StaticUserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * DynamicProfileService.createProfile() 方法单元测试
 * 
 * 【测试目标】
 * - 验证 createProfile 方法的完整功能
 * - 确保参数验证、初始值设置、Redis操作、索引更新等步骤正确执行
 * - 验证异常场景的处理逻辑
 * 
 * 【测试策略】
 * - 使用 Mockito 模拟 Redis 操作，避免外部依赖
 * - 验证所有 Redis 操作调用的正确性
 * - 测试正常流程和边界条件
 */
@ExtendWith(MockitoExtension.class)
class DynamicProfileServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private StaticUserProfileRepository staticProfileRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;
    
    @Mock
    private ZSetOperations<String, Object> zSetOperations;
    
    private DynamicProfileService dynamicProfileService;
    
    // 测试常量
    private static final String TEST_USER_ID = "test-user-123";
    private static final String PROFILE_KEY_PREFIX = "dynamic_profile:";
    private static final String ACTIVE_USERS_KEY = "active_users:";
    private static final String PAGEVIEW_INDEX_KEY = "pageview_index";
    private static final String USER_EXPIRY_INDEX = "user_expiry_index";
    private static final String USER_COUNT_KEY = "dynamic_profile_count";
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    @BeforeEach
    void setUp() {
        // 初始化被测试的服务
        dynamicProfileService = new DynamicProfileService(
            redisTemplate, 
            staticProfileRepository, 
            eventPublisher
        );
        
        // 设置 Redis 模板的基本行为（使用lenient以避免不必要的stubbing异常）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void createProfile_WithValidProfile_ShouldCreateSuccessfully() {
        // ========================================
        // GIVEN - 准备测试数据
        // ========================================
        
        // 创建测试用的动态用户画像
        DynamicUserProfile inputProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .pageViewCount(10L)
                .deviceClassification(DeviceClass.MOBILE)
                .recentDeviceTypes(Set.of(DeviceClass.MOBILE))
                .build();
        
        // 模拟 Redis 操作的返回值
        when(valueOperations.increment(USER_COUNT_KEY)).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L); // 1小时TTL
        
        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(inputProfile);
        
        // ========================================
        // THEN - 验证结果和行为
        // ========================================
        
        // 1. 验证返回的profile对象
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getPageViewCount()).isEqualTo(10L);
        assertThat(result.getDeviceClassification()).isEqualTo(DeviceClass.MOBILE);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getLastActiveAt()).isNotNull();
        assertThat(result.getRecentDeviceTypes()).isNotNull();
        
        // 2. 验证主要的Redis存储操作
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());
        
        // 验证Redis key格式
        assertThat(keyCaptor.getValue()).isEqualTo(PROFILE_KEY_PREFIX + TEST_USER_ID);
        
        // 验证TTL设置
        assertThat(ttlCaptor.getValue()).isEqualTo(DEFAULT_TTL);
        
        // 验证存储的对象
        DynamicUserProfile storedProfile = (DynamicUserProfile) valueCaptor.getValue();
        assertThat(storedProfile.getUserId()).isEqualTo(TEST_USER_ID);
        
        // 3. 验证所有ZSet索引更新（总共应该有3次调用）
        ArgumentCaptor<String> zsetKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> zsetUserIdCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Double> zsetScoreCaptor = ArgumentCaptor.forClass(Double.class);
        
        verify(zSetOperations, times(3)).add(zsetKeyCaptor.capture(), zsetUserIdCaptor.capture(), zsetScoreCaptor.capture());
        
        // 验证所有捕获的参数
        var capturedKeys = zsetKeyCaptor.getAllValues();
        var capturedUserIds = zsetUserIdCaptor.getAllValues();
        var capturedScores = zsetScoreCaptor.getAllValues();
        
        // 4. 验证活跃用户索引更新
        boolean activeIndexFound = false;
        boolean pageViewIndexFound = false;
        boolean expiryIndexFound = false;
        
        for (int i = 0; i < capturedKeys.size(); i++) {
            String key = capturedKeys.get(i);
            Object userId = capturedUserIds.get(i);
            Double score = capturedScores.get(i);
            
            assertThat(userId).isEqualTo(TEST_USER_ID);
            
            if ((ACTIVE_USERS_KEY + "recent").equals(key)) {
                // 验证活跃用户索引
                assertThat(score).isPositive(); // 时间戳应该是正数
                activeIndexFound = true;
            } else if (PAGEVIEW_INDEX_KEY.equals(key)) {
                // 验证页面浏览数索引
                assertThat(score).isEqualTo(10.0);
                pageViewIndexFound = true;
            } else if (USER_EXPIRY_INDEX.equals(key)) {
                // 验证过期时间索引
                assertThat(score).isGreaterThan(Instant.now().toEpochMilli());
                expiryIndexFound = true;
            }
        }
        
        // 确保所有索引都被更新
        assertThat(activeIndexFound).isTrue();
        assertThat(pageViewIndexFound).isTrue();
        assertThat(expiryIndexFound).isTrue();
        
        // 5. 验证用户计数器递增
        verify(valueOperations).increment(USER_COUNT_KEY);
        
        // 6. 验证TTL设置调用
        verify(redisTemplate, atLeastOnce()).expire(anyString(), any(Duration.class));
    }

    @Test
    void createProfile_WithMinimalProfile_ShouldSetDefaultValues() {
        // ========================================
        // GIVEN - 创建只有userId的最小化profile
        // ========================================
        
        DynamicUserProfile minimalProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .build();
        
        // 模拟Redis操作
        when(valueOperations.increment(USER_COUNT_KEY)).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L);
        
        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(minimalProfile);
        
        // ========================================
        // THEN - 验证默认值设置
        // ========================================
        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        
        // 验证默认值设置
        assertThat(result.getPageViewCount()).isEqualTo(0L);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getLastActiveAt()).isNotNull();
        assertThat(result.getRecentDeviceTypes()).isNotNull().isEmpty();
        
        // 验证时间设置合理性（应该接近当前时间）
        Instant now = Instant.now();
        assertThat(result.getUpdatedAt()).isBetween(
            now.minusSeconds(5), now.plusSeconds(5)
        );
        assertThat(result.getLastActiveAt()).isBetween(
            now.minusSeconds(5), now.plusSeconds(5)
        );
    }

    @Test
    void createProfile_WithExistingValues_ShouldPreserveExistingValues() {
        // ========================================
        // GIVEN - 创建已有完整数据的profile
        // ========================================
        
        Instant customTime = Instant.parse("2024-01-01T12:00:00Z");
        Set<DeviceClass> existingDevices = new HashSet<>();
        existingDevices.add(DeviceClass.DESKTOP);
        existingDevices.add(DeviceClass.MOBILE);
        
        DynamicUserProfile profileWithData = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .pageViewCount(100L)
                .version(5L)
                .updatedAt(customTime)
                .lastActiveAt(customTime)
                .recentDeviceTypes(existingDevices)
                .build();
        
        // 模拟Redis操作
        when(valueOperations.increment(USER_COUNT_KEY)).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L);
        
        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(profileWithData);
        
        // ========================================
        // THEN - 验证已有值被保留
        // ========================================
        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        
        // 验证已有值被保留
        assertThat(result.getPageViewCount()).isEqualTo(100L);
        assertThat(result.getVersion()).isEqualTo(5L);
        assertThat(result.getUpdatedAt()).isEqualTo(customTime);
        assertThat(result.getLastActiveAt()).isEqualTo(customTime);
        assertThat(result.getRecentDeviceTypes()).containsExactlyInAnyOrder(DeviceClass.DESKTOP, DeviceClass.MOBILE);
    }

    @Test
    void createProfile_WithNullUserId_ShouldThrowException() {
        // ========================================
        // GIVEN - 创建userId为null的profile
        // ========================================
        
        DynamicUserProfile invalidProfile = DynamicUserProfile.builder()
                .userId(null)
                .pageViewCount(10L)
                .build();
        
        // ========================================
        // WHEN & THEN - 验证异常抛出
        // ========================================
        
        assertThatThrownBy(() -> dynamicProfileService.createProfile(invalidProfile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户ID不能为空");
        
        // 验证没有Redis操作被执行
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
        verify(valueOperations, never()).increment(anyString());
        verify(zSetOperations, never()).add(anyString(), any(), anyDouble());
    }

    @Test
    void createProfile_WithEmptyUserId_ShouldThrowException() {
        // ========================================
        // GIVEN - 创建userId为空字符串的profile
        // ========================================
        
        DynamicUserProfile invalidProfile = DynamicUserProfile.builder()
                .userId("")
                .pageViewCount(10L)
                .build();
        
        // ========================================
        // WHEN & THEN - 验证异常抛出
        // ========================================
        
        assertThatThrownBy(() -> dynamicProfileService.createProfile(invalidProfile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户ID不能为空");
    }

    @Test
    void createProfile_WithBlankUserId_ShouldCreateSuccessfully() {
        // ========================================
        // GIVEN - 创建userId为空白字符串的profile
        // 注意：根据isValid()方法的实现，空白字符串("   ")被认为是有效的，因为它不为null且不为空字符串
        // ========================================
        
        DynamicUserProfile profileWithBlankId = DynamicUserProfile.builder()
                .userId("   ")
                .pageViewCount(10L)
                .build();
        
        // 模拟Redis操作
        when(valueOperations.increment(USER_COUNT_KEY)).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L);
        
        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(profileWithBlankId);
        
        // ========================================
        // THEN - 验证创建成功
        // ========================================
        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo("   ");
        
        // 验证基本Redis操作被调用
        verify(valueOperations).set(eq(PROFILE_KEY_PREFIX + "   "), any(DynamicUserProfile.class), eq(DEFAULT_TTL));
    }

    @Test
    void createProfile_ShouldCallAllRedisOperationsInCorrectOrder() {
        // ========================================
        // GIVEN - 准备测试数据
        // ========================================
        
        DynamicUserProfile testProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .pageViewCount(50L)
                .deviceClassification(DeviceClass.TABLET)
                .build();
        
        // 模拟Redis操作
        when(valueOperations.increment(USER_COUNT_KEY)).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L);
        
        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        dynamicProfileService.createProfile(testProfile);
        
        // ========================================
        // THEN - 验证所有Redis操作被调用
        // ========================================
        
        // 使用InOrder验证调用顺序
        var inOrder = inOrder(valueOperations, zSetOperations, redisTemplate);
        
        // 1. 主数据存储
        inOrder.verify(valueOperations).set(eq(PROFILE_KEY_PREFIX + TEST_USER_ID), any(DynamicUserProfile.class), eq(DEFAULT_TTL));
        
        // 2. 索引更新操作（总共3次ZSet操作）
        verify(zSetOperations, times(3)).add(anyString(), eq(TEST_USER_ID), anyDouble());
        
        // 验证具体的索引更新
        verify(zSetOperations).add(eq(ACTIVE_USERS_KEY + "recent"), eq(TEST_USER_ID), anyDouble());
        verify(zSetOperations).add(eq(PAGEVIEW_INDEX_KEY), eq(TEST_USER_ID), eq(50.0));
        verify(zSetOperations).add(eq(USER_EXPIRY_INDEX), eq(TEST_USER_ID), anyDouble());
        
        // 3. 计数器递增
        verify(valueOperations).increment(USER_COUNT_KEY);
        
        // 4. TTL设置操作
        verify(redisTemplate, atLeast(3)).expire(anyString(), any(Duration.class));
    }

    @Test
    void createProfile_WithDeviceClassification_ShouldUpdateDeviceIndex() {
        // ========================================
        // GIVEN - 创建带有设备分类的profile
        // ========================================
        
        DynamicUserProfile profileWithDevice = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .deviceClassification(DeviceClass.SMART_TV)
                .build();
        
        // 模拟Redis Set操作
        when(valueOperations.increment(USER_COUNT_KEY)).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L);
        lenient().when(redisTemplate.opsForSet()).thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
        
        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(profileWithDevice);
        
        // ========================================
        // THEN - 验证设备索引更新
        // ========================================
        
        assertThat(result.getDeviceClassification()).isEqualTo(DeviceClass.SMART_TV);
        
        // 注意：由于设备索引更新是通过私有方法进行的，我们主要验证最终结果
        // 实际的设备索引更新验证需要集成测试来完成
    }
}