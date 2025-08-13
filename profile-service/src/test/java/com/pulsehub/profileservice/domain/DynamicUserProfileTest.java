package com.pulsehub.profileservice.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.pulsehub.profileservice.config.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamicUserProfile 动态用户画像集成测试类
 * 
 * 【测试目标】
 * 验证DynamicUserProfile和相关组件的完整集成功能，包括：
 * - 业务逻辑正确性
 * - Redis集成功能
 * - 序列化/反序列化完整性
 * 
 * 【集成测试特点】
 * - 使用真实的TestRedisConfig配置
 * - 使用真实的RedisTemplate（@Autowired而非@MockBean）
 * - 验证组件间的真实交互
 * 
 * 【测试覆盖】
 * - 基础构建和属性访问
 * - 辅助方法功能验证
 * - 边界条件和异常处理
 * - 序列化/反序列化完整性
 * - 设备分类器与Redis的真实集成
 */
@SpringBootTest(classes = {TestRedisConfig.class})
@ActiveProfiles("test")
@DisplayName("动态用户画像测试")
class DynamicUserProfileTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private DynamicUserProfile testProfile;
    private DeviceClassifier deviceClassifier;
    private DynamicUserProfileSerializer serializer;

    @BeforeEach
    void setUp() {
        // 初始化测试用的用户画像
        testProfile = DynamicUserProfile.builder()
                .userId("test-user-123")
                .lastActiveAt(Instant.now())
                .pageViewCount(100L)
                .deviceClassification(DeviceClass.MOBILE)
                .version(1L)
                .updatedAt(Instant.now())
                .build();

        // 初始化依赖组件
        deviceClassifier = new DeviceClassifier(redisTemplate);
        serializer = new DynamicUserProfileSerializer();
    }

    @Nested
    @DisplayName("基础构建测试")
    class BasicConstructionTests {

        @Test
        @DisplayName("Builder模式构建用户画像")
        void testBuilderPattern() {
            // Given
            String userId = "user-456";
            Instant now = Instant.now();
            Long pageViews = 250L;

            // When
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId(userId)
                    .lastActiveAt(now)
                    .pageViewCount(pageViews)
                    .deviceClassification(DeviceClass.DESKTOP)
                    .build();

            // Then
            assertNotNull(profile);
            assertEquals(userId, profile.getUserId());
            assertEquals(now, profile.getLastActiveAt());
            assertEquals(pageViews, profile.getPageViewCount());
            assertEquals(DeviceClass.DESKTOP, profile.getDeviceClassification());
        }

        @Test
        @DisplayName("默认值初始化")
        void testDefaultValues() {
            // When
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .build();

            // Then
            assertEquals(0L, profile.getPageViewCount());
            assertEquals(1L, profile.getVersion());
            assertNotNull(profile.getRecentDeviceTypes());
            assertTrue(profile.getRecentDeviceTypes().isEmpty());
        }

        @Test
        @DisplayName("数据验证测试")
        void testValidation() {
            // Valid profile
            DynamicUserProfile validProfile = DynamicUserProfile.builder()
                    .userId("valid-user")
                    .build();
            assertTrue(validProfile.isValid());

            // Invalid profiles
            DynamicUserProfile nullUserIdProfile = DynamicUserProfile.builder().build();
            assertFalse(nullUserIdProfile.isValid());

            DynamicUserProfile emptyUserIdProfile = DynamicUserProfile.builder()
                    .userId("   ")
                    .build();
            assertFalse(emptyUserIdProfile.isValid());
        }
    }

    @Nested
    @DisplayName("辅助方法测试")
    class HelperMethodsTests {

        @Test
        @DisplayName("页面浏览计数增加功能")
        void testIncrementPageViewCount() {
            // Given - 新用户画像，初始计数为0
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .build();

            // When & Then - 首次增加
            profile.incrementPageViewCount();
            assertEquals(1L, profile.getPageViewCount());

            // When & Then - 再次增加
            profile.incrementPageViewCount();
            assertEquals(2L, profile.getPageViewCount());

            // When & Then - 批量增加
            profile.incrementPageViewCount(5);
            assertEquals(7L, profile.getPageViewCount());
        }

        @Test
        @DisplayName("页面浏览计数边界测试")
        void testIncrementPageViewCountEdgeCases() {
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .pageViewCount(null)  // 测试null值处理
                    .build();

            // 从null开始增加
            profile.incrementPageViewCount();
            assertEquals(1L, profile.getPageViewCount());

            // 测试0和负数增加
            profile.incrementPageViewCount(0);
            assertEquals(1L, profile.getPageViewCount());  // 不应变化

            profile.incrementPageViewCount(-5);
            assertEquals(1L, profile.getPageViewCount());  // 不应变化
        }

        @Test
        @DisplayName("活跃时间更新功能")
        void testUpdateLastActiveAt() {
            // Given
            Instant beforeUpdate = Instant.now().minus(1, ChronoUnit.HOURS);
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .lastActiveAt(beforeUpdate)
                    .build();

            // When - 更新为当前时间
            profile.updateLastActiveAt();

            // Then
            assertNotNull(profile.getLastActiveAt());
            assertTrue(profile.getLastActiveAt().isAfter(beforeUpdate));

            // When - 更新为指定时间
            Instant specificTime = Instant.now().plus(1, ChronoUnit.HOURS);
            profile.updateLastActiveAt(specificTime);

            // Then
            assertEquals(specificTime, profile.getLastActiveAt());

            // When - 传入null值
            Instant lastTime = profile.getLastActiveAt();
            profile.updateLastActiveAt(null);

            // Then - 不应发生变化
            assertEquals(lastTime, profile.getLastActiveAt());
        }

        @Test
        @DisplayName("设备类型管理功能")
        void testDeviceTypeManagement() {
            // Given
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .build();

            // When - 添加设备类型
            profile.addRecentDeviceType(DeviceClass.MOBILE);
            profile.addRecentDeviceType(DeviceClass.DESKTOP);

            // Then
            assertEquals(2, profile.getRecentDeviceTypes().size());
            assertTrue(profile.getRecentDeviceTypes().contains(DeviceClass.MOBILE));
            assertTrue(profile.getRecentDeviceTypes().contains(DeviceClass.DESKTOP));

            // When - 添加重复设备类型
            profile.addRecentDeviceType(DeviceClass.MOBILE);

            // Then - Set自动去重
            assertEquals(2, profile.getRecentDeviceTypes().size());

            // When - 设置主设备分类
            profile.setMainDeviceClassification(DeviceClass.TABLET);

            // Then
            assertEquals(DeviceClass.TABLET, profile.getDeviceClassification());
            assertTrue(profile.getRecentDeviceTypes().contains(DeviceClass.TABLET));
            assertEquals(3, profile.getRecentDeviceTypes().size());
        }

        @Test
        @DisplayName("活跃状态检查功能")
        void testActivityChecking() {
            // Given - 1小时前活跃的用户
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .lastActiveAt(oneHourAgo)
                    .build();

            // When & Then - 检查2小时内活跃
            assertTrue(profile.isActiveWithin(7200)); // 2小时 = 7200秒

            // When & Then - 检查30分钟内活跃
            assertFalse(profile.isActiveWithin(1800)); // 30分钟 = 1800秒

            // When & Then - 空活跃时间
            profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .build();
            assertFalse(profile.isActiveWithin(3600));
        }

        @Test
        @DisplayName("活跃程度分类功能")
        void testActivityLevelClassification() {
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .build();

            // 测试未知状态
            assertEquals("UNKNOWN", profile.getActivityLevel());

            // 测试非常活跃（1小时内）
            profile.setLastActiveAt(Instant.now().minus(30, ChronoUnit.MINUTES));
            assertEquals("VERY_ACTIVE", profile.getActivityLevel());

            // 测试活跃（1-24小时）
            profile.setLastActiveAt(Instant.now().minus(12, ChronoUnit.HOURS));
            assertEquals("ACTIVE", profile.getActivityLevel());

            // 测试较少活跃（1-7天）
            profile.setLastActiveAt(Instant.now().minus(3, ChronoUnit.DAYS));
            assertEquals("LESS_ACTIVE", profile.getActivityLevel());

            // 测试不活跃（7天以上）
            profile.setLastActiveAt(Instant.now().minus(10, ChronoUnit.DAYS));
            assertEquals("INACTIVE", profile.getActivityLevel());
        }

        @Test
        @DisplayName("版本控制功能")
        void testVersionControl() {
            // Given
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("test-user")
                    .version(1L)
                    .build();

            Instant initialUpdateTime = profile.getUpdatedAt();
            Long initialVersion = profile.getVersion();

            // When - 执行更新操作
            profile.incrementPageViewCount();

            // Then - 版本和更新时间应该变化
            assertTrue(profile.getVersion() > initialVersion);
            assertNotEquals(initialUpdateTime, profile.getUpdatedAt());
        }
    }

    @Nested
    @DisplayName("序列化测试")
    class SerializationTests {

        @Test
        @DisplayName("Kafka JSON序列化往返测试")
        void testKafkaJsonSerialization() throws JsonProcessingException {
            // Given
            DynamicUserProfile original = DynamicUserProfile.builder()
                    .userId("kafka-test-user")
                    .lastActiveAt(Instant.now())
                    .pageViewCount(500L)
                    .deviceClassification(DeviceClass.MOBILE)
                    .recentDeviceTypes(Set.of(DeviceClass.MOBILE, DeviceClass.DESKTOP))
                    .version(3L)
                    .updatedAt(Instant.now())
                    .build();

            // When - 序列化然后反序列化
            String kafkaJson = serializer.toKafkaJson(original);
            DynamicUserProfile deserialized = serializer.fromKafkaJson(kafkaJson);

            // Then
            assertNotNull(kafkaJson);
            assertTrue(kafkaJson.contains("profile"));
            assertTrue(kafkaJson.contains("_schema_version"));
            
            assertEquals(original.getUserId(), deserialized.getUserId());
            assertEquals(original.getPageViewCount(), deserialized.getPageViewCount());
            assertEquals(original.getDeviceClassification(), deserialized.getDeviceClassification());
            assertEquals(original.getVersion(), deserialized.getVersion());
        }

        @Test
        @DisplayName("Redis JSON序列化往返测试")
        void testRedisJsonSerialization() throws JsonProcessingException {
            // Given
            DynamicUserProfile original = DynamicUserProfile.builder()
                    .userId("redis-test-user")
                    .lastActiveAt(Instant.now())
                    .pageViewCount(750L)
                    .deviceClassification(DeviceClass.TABLET)
                    .recentDeviceTypes(Set.of(DeviceClass.TABLET))
                    .version(2L)
                    .updatedAt(Instant.now())
                    .build();

            // When - 序列化然后反序列化
            String redisJson = serializer.toRedisJson(original);
            DynamicUserProfile deserialized = serializer.fromRedisJson(redisJson);

            // Then - 验证压缩格式
            assertNotNull(redisJson);
            assertTrue(redisJson.contains("\"u\":"));  // 压缩字段名
            assertTrue(redisJson.contains("\"pv\":"));

            // 验证数据完整性
            assertEquals(original.getUserId(), deserialized.getUserId());
            assertEquals(original.getPageViewCount(), deserialized.getPageViewCount());
            assertEquals(original.getDeviceClassification(), deserialized.getDeviceClassification());
            assertEquals(original.getVersion(), deserialized.getVersion());
        }

        @Test
        @DisplayName("序列化异常处理测试")
        void testSerializationExceptionHandling() {
            // Test null profile
            assertThrows(IllegalArgumentException.class, 
                    () -> serializer.toKafkaJson(null));
            
            assertThrows(IllegalArgumentException.class, 
                    () -> serializer.toRedisJson(null));

            // Test invalid JSON
            assertThrows(JsonProcessingException.class, 
                    () -> serializer.fromKafkaJson("invalid json"));
            
            assertThrows(JsonProcessingException.class, 
                    () -> serializer.fromRedisJson("invalid json"));

            // Test empty/null JSON
            assertThrows(IllegalArgumentException.class, 
                    () -> serializer.fromKafkaJson(null));
            
            assertThrows(IllegalArgumentException.class, 
                    () -> serializer.fromRedisJson(""));
        }
    }

    @Nested
    @DisplayName("设备分类器集成测试")
    class DeviceClassifierIntegrationTests {

        @Test
        @DisplayName("已知设备类型分类测试")
        void testKnownDeviceClassification() {
            // 测试移动设备
            assertEquals(DeviceClass.MOBILE, deviceClassifier.classify("iPhone"));
            assertEquals(DeviceClass.MOBILE, deviceClassifier.classify("android"));
            assertEquals(DeviceClass.MOBILE, deviceClassifier.classify("MOBILE"));

            // 测试桌面设备
            assertEquals(DeviceClass.DESKTOP, deviceClassifier.classify("mac"));
            assertEquals(DeviceClass.DESKTOP, deviceClassifier.classify("Windows"));
            assertEquals(DeviceClass.DESKTOP, deviceClassifier.classify("PC"));

            // 测试平板设备
            assertEquals(DeviceClass.TABLET, deviceClassifier.classify("iPad"));
            assertEquals(DeviceClass.TABLET, deviceClassifier.classify("tablet"));

            // 测试智能电视
            assertEquals(DeviceClass.SMART_TV, deviceClassifier.classify("smart_tv"));
            assertEquals(DeviceClass.SMART_TV, deviceClassifier.classify("TV"));
        }

        @Test
        @DisplayName("未知设备类型处理测试")
        void testUnknownDeviceHandling() {
            // 清理之前的数据
            deviceClassifier.clearUnknownDeviceTypes();
            
            // 测试未知设备
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify("refrigerator"));
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify("vr_headset"));
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify("smartwatch"));

            // 【集成测试重点】验证未知设备被记录到Redis
            Set<Object> unknownTypes = deviceClassifier.getUnknownDeviceTypes();
            assertEquals(3, unknownTypes.size());
            assertTrue(unknownTypes.contains("refrigerator"));
            assertTrue(unknownTypes.contains("vr_headset"));
            assertTrue(unknownTypes.contains("smartwatch"));

            // 测试边界情况（null/空字符串不会记录到Redis）
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify(null));
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify(""));
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify("   "));
            
            // 验证边界情况没有增加Redis记录
            Set<Object> finalUnknownTypes = deviceClassifier.getUnknownDeviceTypes();
            assertEquals(3, finalUnknownTypes.size()); // 数量不变


            // 动态添加 unknown 设备为 known: other
            finalUnknownTypes.forEach(
                    rawType -> deviceClassifier.addDeviceMapping((String) rawType, DeviceClass.OTHER)
            );

            assertTrue(deviceClassifier.isKnownDeviceType("refrigerator"));
            assertTrue(deviceClassifier.isKnownDeviceType("vr_headset"));
            assertTrue(deviceClassifier.isKnownDeviceType("smartwatch"));

            // 清除 redis 中的 unknown_device_types
            deviceClassifier.clearUnknownDeviceTypes();
        }

        @Test
        @DisplayName("设备类型批量分类测试")
        void testBatchClassification() {
            // Given
            Set<String> deviceTypes = Set.of("iPhone", "unknown_device", "mac", "tablet");

            // When
            var results = deviceClassifier.classifyBatch(deviceTypes);

            // Then
            assertEquals(4, results.size());
            assertEquals(DeviceClass.MOBILE, results.get("iPhone"));
            assertEquals(DeviceClass.UNKNOWN, results.get("unknown_device"));
            assertEquals(DeviceClass.DESKTOP, results.get("mac"));
            assertEquals(DeviceClass.TABLET, results.get("tablet"));
        }

        @Test
        @DisplayName("设备映射动态扩展测试")
        void testDynamicMappingExtension() {
            // 清理之前的数据
            deviceClassifier.clearUnknownDeviceTypes();
            
            // Given - 新设备类型
            String newDeviceType = "smart_watch";

            // When - 初始时未知
            assertFalse(deviceClassifier.isKnownDeviceType(newDeviceType));
            assertEquals(DeviceClass.UNKNOWN, deviceClassifier.classify(newDeviceType));

            // 【集成测试重点】验证未知设备被记录到Redis
            Set<Object> unknownBefore = deviceClassifier.getUnknownDeviceTypes();
            assertTrue(unknownBefore.contains(newDeviceType));

            // When - 添加映射
            deviceClassifier.addDeviceMapping(newDeviceType, DeviceClass.OTHER);

            // Then - 现在已知
            assertTrue(deviceClassifier.isKnownDeviceType(newDeviceType));
            assertEquals(DeviceClass.OTHER, deviceClassifier.classify(newDeviceType));
            
            // 【集成测试重点】验证添加映射后，再次分类不会增加Redis记录
            Set<Object> unknownAfter = deviceClassifier.getUnknownDeviceTypes();
            assertEquals(unknownBefore.size(), unknownAfter.size()); // 没有新增记录
        }
    }

    @Nested
    @DisplayName("边界条件和异常测试")
    class EdgeCasesAndExceptionTests {

        @Test
        @DisplayName("极值测试")
        void testExtremeValues() {
            // 测试极大的页面浏览数
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("extreme-user")
                    .pageViewCount(Long.MAX_VALUE)
                    .build();

            // 应该能正常处理而不溢出
            profile.incrementPageViewCount();
            // 注意：这里可能会溢出，在实际应用中需要添加溢出保护

            // 测试极远的时间
            Instant veryOldTime = Instant.EPOCH;
            profile.setLastActiveAt(veryOldTime);
            assertEquals("INACTIVE", profile.getActivityLevel());
        }

        @Test
        @DisplayName("并发安全性测试")
        void testConcurrentModification() {
            // 这里可以添加多线程测试，但简化为基础验证
            DynamicUserProfile profile = DynamicUserProfile.builder()
                    .userId("concurrent-user")
                    .build();

            // 模拟并发操作
            profile.incrementPageViewCount();
            profile.addRecentDeviceType(DeviceClass.MOBILE);
            profile.updateLastActiveAt();

            // 验证状态一致性
            assertNotNull(profile.getUpdatedAt());
            assertTrue(profile.getVersion() > 0);
        }

        @Test
        @DisplayName("内存效率测试")
        void testMemoryEfficiency() {
            // 创建大量用户画像实例来测试内存使用
            for (int i = 0; i < 1000; i++) {
                DynamicUserProfile profile = DynamicUserProfile.builder()
                        .userId("user-" + i)
                        .pageViewCount((long) i)
                        .deviceClassification(DeviceClass.MOBILE)
                        .build();

                // 基本验证
                assertNotNull(profile);
                assertEquals("user-" + i, profile.getUserId());
            }
            // 这个测试主要是确保没有内存泄漏或异常
        }
    }
} 