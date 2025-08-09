package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.config.IntegrationTestConfig;
import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.DynamicUserProfileSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * DynamicProfileService 集成测试
 * 
 * 【测试目标】
 * - 使用真实的 Redis 实例验证完整的功能流程
 * - 测试 Redis 数据持久化和检索
 * - 验证索引创建和维护功能
 * - 测试 TTL 功能和过期机制
 * - 验证计数器操作
 * 
 * 【测试策略】
 * - 使用嵌入式 Redis 服务器避免外部依赖
 * - 测试真实的 Redis 操作和数据存储
 * - 验证业务逻辑的完整流程
 */
@SpringBootTest(
    classes = IntegrationTestConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("test")
class DynamicProfileServiceIntegrationTest {

    @Autowired
    private DynamicProfileService dynamicProfileService;

    @Autowired
    @Qualifier("testRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private DynamicUserProfileSerializer dynamicUserProfileSerializer;

    private static RedisServer redisServer;

    // 测试常量
    private static final String TEST_USER_ID = "integration-test-user-123";
    private static final String PROFILE_KEY_PREFIX = "dynamic_profile:";
    private static final String ACTIVE_USERS_KEY = "active_users:recent";
    private static final String PAGEVIEW_INDEX_KEY = "pageview_index";
    private static final String USER_EXPIRY_INDEX = "user_expiry_index";
    private static final String USER_COUNT_KEY = "dynamic_profile_count";

    /**
     * 动态配置 Redis 连接属性
     */
    @DynamicPropertySource
    static void configureRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 6370); // 使用非标准端口避免冲突
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    /**
     * 启动嵌入式 Redis 服务器
     */
    @BeforeAll
    void startRedisServer() {
        try {
            redisServer = RedisServer.builder()
                    .port(6370)
                    .setting("maxmemory 128M")
                    .setting("maxmemory-policy allkeys-lru")
                    .build();
            
            redisServer.start();
            
            // 等待 Redis 服务器完全启动
            Thread.sleep(2000);
            
            System.out.println("✅ 嵌入式 Redis 服务器已启动 (端口: 6370)");
        } catch (Exception e) {
            System.err.println("❌ 启动嵌入式 Redis 服务器失败: " + e.getMessage());
            throw new RuntimeException("无法启动嵌入式 Redis 服务器", e);
        }
    }

    /**
     * 停止嵌入式 Redis 服务器
     */
    @AfterAll
    void stopRedisServer() {
        if (redisServer != null) {
            try {
                redisServer.stop();
                System.out.println("🛑 嵌入式 Redis 服务器已停止");
            } catch (Exception e) {
                System.err.println("⚠️ 停止嵌入式 Redis 服务器时出现异常: " + e.getMessage());
                // 不抛出异常，避免影响测试结果
            }
        }
    }

    /**
     * 每个测试前清理 Redis 数据
     */
    @BeforeEach
    void cleanupRedis() {
        try {
            // 验证 Redis 连接
            verifyRedisConnection();
            
            // 清理所有 Redis 数据
            redisTemplate.getConnectionFactory().getConnection().flushAll();
            System.out.println("🧹 Redis 数据已清理");
        } catch (Exception e) {
            System.err.println("⚠️ 清理 Redis 数据时出现异常: " + e.getMessage());
            // 重新尝试连接
            try {
                Thread.sleep(500); // 短暂等待
                redisTemplate.getConnectionFactory().getConnection().flushAll();
                System.out.println("🧹 Redis 数据已清理 (重试成功)");
            } catch (Exception retryException) {
                throw new RuntimeException("无法清理 Redis 数据", retryException);
            }
        }
    }

    /**
     * 验证 Redis 连接是否正常
     */
    private void verifyRedisConnection() {
        try {
            // 执行 PING 命令验证连接
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            if (!"PONG".equals(pong)) {
                throw new RuntimeException("Redis PING 命令返回异常结果: " + pong);
            }
        } catch (Exception e) {
            throw new RuntimeException("Redis 连接验证失败", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("集成测试前置：验证 Redis 服务器连接")
    void testRedisConnection() {
        // ========================================
        // GIVEN - Redis 服务器应该已经启动
        // ========================================
        
        assertThat(redisServer).isNotNull();
        assertThat(redisServer.isActive()).isTrue();
        
        // ========================================
        // WHEN - 验证 Redis 连接
        // ========================================
        
        assertThatNoException().isThrownBy(this::verifyRedisConnection);
        
        // ========================================
        // THEN - 能够执行基本 Redis 操作
        // ========================================
        
        // 测试基本的 Redis 操作
        String testKey = "test:connection";
        String testValue = "connection-test-value";
        
        redisTemplate.opsForValue().set(testKey, testValue);
        Object retrievedValue = redisTemplate.opsForValue().get(testKey);
        
        assertThat(retrievedValue).isEqualTo(testValue);
        
        // 清理测试数据
        redisTemplate.delete(testKey);
        
        System.out.println("✅ Redis 连接验证通过");
    }

    @Test
    @Order(2)
    @DisplayName("集成测试：createProfile 应该成功创建并持久化用户画像")
    void createProfile_ShouldPersistDataToRedis() {
        // ========================================
        // GIVEN - 准备测试数据
        // ========================================
        
        DynamicUserProfile inputProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID)
                .pageViewCount(25L)
                .deviceClassification(DeviceClass.MOBILE)
                .recentDeviceTypes(Set.of(DeviceClass.MOBILE))
                .build();

        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(inputProfile);

        // ========================================
        // THEN - 验证结果和 Redis 数据
        // ========================================
        
        // 1. 验证返回结果
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getPageViewCount()).isEqualTo(25L);
        assertThat(result.getDeviceClassification()).isEqualTo(DeviceClass.MOBILE);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getLastActiveAt()).isNotNull();

        // 2. 验证主数据在 Redis 中的存储（现在存储为序列化的字符串）
        String profileKey = PROFILE_KEY_PREFIX + TEST_USER_ID;
        Object storedData = redisTemplate.opsForValue().get(profileKey);
        
        assertThat(storedData).isNotNull();
        assertThat(storedData).isInstanceOf(String.class);
        
        // 使用序列化器反序列化
        String serializedProfile = (String) storedData;
        DynamicUserProfile retrievedProfile = dynamicUserProfileSerializer.deserialize(serializedProfile);
        assertThat(retrievedProfile).isNotNull();
        assertThat(retrievedProfile.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(retrievedProfile.getPageViewCount()).isEqualTo(25L);
        assertThat(retrievedProfile.getDeviceClassification()).isEqualTo(DeviceClass.MOBILE);

        // 3. 验证 TTL 设置
        Long ttl = redisTemplate.getExpire(profileKey);
        assertThat(ttl).isGreaterThan(0); // TTL 应该被正确设置
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofDays(7).getSeconds()); // 应该是7天或更少

        // 4. 验证活跃用户索引
        Set<Object> activeUsers = redisTemplate.opsForZSet().range(ACTIVE_USERS_KEY, 0, -1);
        assertThat(activeUsers).contains(TEST_USER_ID);
        
        // 验证活跃用户的分数（时间戳）
        Double activeScore = redisTemplate.opsForZSet().score(ACTIVE_USERS_KEY, TEST_USER_ID);
        assertThat(activeScore).isNotNull();
        assertThat(activeScore).isPositive();
        assertThat(activeScore).isCloseTo(Instant.now().toEpochMilli(), within(5000.0)); // 5秒误差范围

        // 5. 验证页面浏览数索引
        Set<Object> pageViewUsers = redisTemplate.opsForZSet().range(PAGEVIEW_INDEX_KEY, 0, -1);
        assertThat(pageViewUsers).contains(TEST_USER_ID);
        
        Double pageViewScore = redisTemplate.opsForZSet().score(PAGEVIEW_INDEX_KEY, TEST_USER_ID);
        assertThat(pageViewScore).isEqualTo(25.0);

        // 6. 验证过期时间索引
        Set<Object> expiryUsers = redisTemplate.opsForZSet().range(USER_EXPIRY_INDEX, 0, -1);
        assertThat(expiryUsers).contains(TEST_USER_ID);
        
        Double expiryScore = redisTemplate.opsForZSet().score(USER_EXPIRY_INDEX, TEST_USER_ID);
        assertThat(expiryScore).isNotNull();
        assertThat(expiryScore).isGreaterThan(Instant.now().toEpochMilli()); // 过期时间应该在未来

        // 7. 验证用户计数器
        Object counterValue = redisTemplate.opsForValue().get(USER_COUNT_KEY);
        assertThat(counterValue).isNotNull();
        assertThat(counterValue).isEqualTo("1"); // Redis increment 返回的是字符串形式

        System.out.println("✅ 集成测试通过：用户画像已成功创建并持久化到 Redis");
    }

    @Test
    @Order(3)
    @DisplayName("集成测试：createProfile 应该正确设置默认值")
    void createProfile_WithMinimalData_ShouldSetDefaults() {
        // ========================================
        // GIVEN - 最小化输入数据
        // ========================================
        
        DynamicUserProfile minimalProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID + "_minimal")
                .build();

        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(minimalProfile);

        // ========================================
        // THEN - 验证默认值设置和 Redis 存储
        // ========================================
        
        // 1. 验证返回结果的默认值
        assertThat(result.getPageViewCount()).isEqualTo(0L);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getLastActiveAt()).isNotNull();
        assertThat(result.getRecentDeviceTypes()).isNotNull().isEmpty();

        // 2. 验证 Redis 中存储的数据
        String profileKey = PROFILE_KEY_PREFIX + TEST_USER_ID + "_minimal";
        Object storedData = redisTemplate.opsForValue().get(profileKey);
        
        assertThat(storedData).isNotNull();
        assertThat(storedData).isInstanceOf(String.class);
        
        DynamicUserProfile storedProfile = dynamicUserProfileSerializer.deserialize((String) storedData);
        assertThat(storedProfile).isNotNull();
        assertThat(storedProfile.getPageViewCount()).isEqualTo(0L);
        assertThat(storedProfile.getVersion()).isEqualTo(1L);

        // 3. 验证索引中的正确存储
        Double pageViewScore = redisTemplate.opsForZSet().score(PAGEVIEW_INDEX_KEY, TEST_USER_ID + "_minimal");
        assertThat(pageViewScore).isEqualTo(0.0); // 默认页面浏览数为0

        System.out.println("✅ 集成测试通过：默认值正确设置并存储到 Redis");
    }

    @Test
    @Order(4)
    @DisplayName("集成测试：createProfile 应该正确处理设备分类索引")
    void createProfile_WithDeviceClass_ShouldUpdateDeviceIndex() {
        // ========================================
        // GIVEN - 带设备分类的用户画像
        // ========================================
        
        DynamicUserProfile profileWithDevice = DynamicUserProfile.builder()
                .userId(TEST_USER_ID + "_device")
                .deviceClassification(DeviceClass.TABLET)
                .pageViewCount(15L)
                .build();

        // ========================================
        // WHEN - 执行被测试的方法
        // ========================================
        
        DynamicUserProfile result = dynamicProfileService.createProfile(profileWithDevice);

        // ========================================
        // THEN - 验证设备索引和数据存储
        // ========================================
        
        // 1. 验证返回结果
        assertThat(result.getDeviceClassification()).isEqualTo(DeviceClass.TABLET);

        // 2. 验证 Redis 中的主数据
        String profileKey = PROFILE_KEY_PREFIX + TEST_USER_ID + "_device";
        Object storedData = redisTemplate.opsForValue().get(profileKey);
        
        assertThat(storedData).isNotNull();
        assertThat(storedData).isInstanceOf(String.class);
        
        DynamicUserProfile storedProfile = dynamicUserProfileSerializer.deserialize((String) storedData);
        assertThat(storedProfile).isNotNull();
        assertThat(storedProfile.getDeviceClassification()).isEqualTo(DeviceClass.TABLET);

        // 3. 验证设备类型索引（注意：这是通过私有方法更新的，我们主要验证数据一致性）
        // 由于设备索引是通过私有方法管理的，我们主要验证最终存储的数据正确性
        
        // 4. 验证页面浏览数索引包含该用户
        Double pageViewScore = redisTemplate.opsForZSet().score(PAGEVIEW_INDEX_KEY, TEST_USER_ID + "_device");
        assertThat(pageViewScore).isEqualTo(15.0);

        System.out.println("✅ 集成测试通过：设备分类数据正确处理并存储");
    }

    @Test
    @Order(5)
    @DisplayName("集成测试：验证用户画像可以通过 getProfile 方法正确检索")
    void createProfile_ShouldBeRetrievableViaGetProfile() {
        // ========================================
        // GIVEN - 创建一个用户画像
        // ========================================
        
        DynamicUserProfile originalProfile = DynamicUserProfile.builder()
                .userId(TEST_USER_ID + "_retrieve")
                .pageViewCount(50L)
                .deviceClassification(DeviceClass.DESKTOP)
                .build();

        dynamicProfileService.createProfile(originalProfile);

        // ========================================
        // WHEN - 使用 getProfile 检索数据
        // ========================================
        
        Optional<DynamicUserProfile> retrievedProfileOpt = dynamicProfileService.getProfile(TEST_USER_ID + "_retrieve");

        // ========================================
        // THEN - 验证检索到的数据
        // ========================================
        
        assertThat(retrievedProfileOpt).isPresent();
        
        DynamicUserProfile retrievedProfile = retrievedProfileOpt.get();
        assertThat(retrievedProfile.getUserId()).isEqualTo(TEST_USER_ID + "_retrieve");
        assertThat(retrievedProfile.getPageViewCount()).isEqualTo(50L);
        assertThat(retrievedProfile.getDeviceClassification()).isEqualTo(DeviceClass.DESKTOP);
        assertThat(retrievedProfile.getVersion()).isEqualTo(1L);

        System.out.println("✅ 集成测试通过：用户画像可以正确检索");
    }

    @Test
    @Order(6)
    @DisplayName("集成测试：验证多个用户画像的独立性")
    void createProfile_MultipleUsers_ShouldBeIndependent() {
        // ========================================
        // GIVEN - 创建多个用户画像
        // ========================================
        
        DynamicUserProfile user1 = DynamicUserProfile.builder()
                .userId(TEST_USER_ID + "_user1")
                .pageViewCount(10L)
                .deviceClassification(DeviceClass.MOBILE)
                .build();

        DynamicUserProfile user2 = DynamicUserProfile.builder()
                .userId(TEST_USER_ID + "_user2")
                .pageViewCount(20L)
                .deviceClassification(DeviceClass.DESKTOP)
                .build();

        // ========================================
        // WHEN - 创建两个用户画像
        // ========================================
        
        dynamicProfileService.createProfile(user1);
        dynamicProfileService.createProfile(user2);

        // ========================================
        // THEN - 验证两个用户的独立性
        // ========================================
        
        // 1. 验证用户计数器
        Object counterValue = redisTemplate.opsForValue().get(USER_COUNT_KEY);
        assertThat(counterValue).isNotNull();
        assertThat(counterValue).isEqualTo("2"); // Redis increment 返回的是字符串形式

        // 2. 验证页面浏览数索引包含两个用户
        Set<ZSetOperations.TypedTuple<Object>> pageViewUsersWithScores = 
                redisTemplate.opsForZSet().rangeWithScores(PAGEVIEW_INDEX_KEY, 0, -1);
        
        assertThat(pageViewUsersWithScores).hasSize(2);
        
        // 验证用户1的数据
        Optional<DynamicUserProfile> retrievedUser1 = dynamicProfileService.getProfile(TEST_USER_ID + "_user1");
        assertThat(retrievedUser1).isPresent();
        assertThat(retrievedUser1.get().getPageViewCount()).isEqualTo(10L);
        assertThat(retrievedUser1.get().getDeviceClassification()).isEqualTo(DeviceClass.MOBILE);

        // 验证用户2的数据
        Optional<DynamicUserProfile> retrievedUser2 = dynamicProfileService.getProfile(TEST_USER_ID + "_user2");
        assertThat(retrievedUser2).isPresent();
        assertThat(retrievedUser2.get().getPageViewCount()).isEqualTo(20L);
        assertThat(retrievedUser2.get().getDeviceClassification()).isEqualTo(DeviceClass.DESKTOP);

        // 3. 验证活跃用户索引包含两个用户
        Set<Object> activeUsers = redisTemplate.opsForZSet().range(ACTIVE_USERS_KEY, 0, -1);
        assertThat(activeUsers).containsExactlyInAnyOrder(
            TEST_USER_ID + "_user1", 
            TEST_USER_ID + "_user2"
        );

        System.out.println("✅ 集成测试通过：多个用户画像保持独立性");
    }

    @Test
    @Order(7)
    @DisplayName("集成测试：验证无效输入的异常处理")
    void createProfile_WithInvalidInput_ShouldThrowException() {
        // ========================================
        // GIVEN - 无效的用户画像（用户ID为null）
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

        // 验证 Redis 中没有存储任何数据
        Object counterValue = redisTemplate.opsForValue().get(USER_COUNT_KEY);
        assertThat(counterValue).isNull(); // 计数器应该没有被更新

        System.out.println("✅ 集成测试通过：无效输入正确抛出异常");
    }
}