package com.pulsehub.common.proto;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserProfileSyncEvent Protobuf 消息测试类
 * 
 * 【测试目标】
 * 验证 UserProfileSyncEvent 及其相关组件的功能，包括：
 * - 消息构建和字段访问
 * - 序列化/反序列化完整性
 * - OneOf 字段处理
 * - 边界条件和异常处理
 * 
 * 【测试覆盖】
 * - 基础消息构建和验证
 * - FullSync 和 IncrementalSync 的 OneOf 处理
 * - 序列化往返测试
 * - 各种 SyncType 枚举处理
 * - 元数据和时间戳处理
 */
@DisplayName("UserProfileSyncEvent Protobuf 消息测试")
class UserProfileSyncEventTest {

    private Timestamp testTimestamp;
    private SyncMetadata testMetadata;
    private FullProfileSync testFullSync;
    private IncrementalSync testIncrementalSync;

    @BeforeEach
    void setUp() {
        // 创建测试用的时间戳
        Instant now = Instant.now();
        testTimestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();

        // 创建测试用的同步元数据
        testMetadata = SyncMetadata.newBuilder()
                .setSource("test-service")
                .setTriggerReason("test")
                .setDataVersion("1.0")
                .setSchemaVersion("1.0")
                .build();

        // 创建测试用的完整同步数据
        CoreProfileFields coreFields = CoreProfileFields.newBuilder()
                .setDataVersion("v1.0")
                .setStatus("ACTIVE")
                .addTags("test-user")
                .build();

        Struct dynamicFields = Struct.newBuilder()
                .putFields("pageViews", Value.newBuilder().setNumberValue(100).build())
                .putFields("deviceType", Value.newBuilder().setStringValue("mobile").build())
                .build();

        testFullSync = FullProfileSync.newBuilder()
                .setCoreFields(coreFields)
                .setStaticProfile(dynamicFields)
                .build();

        // 创建测试用的增量同步数据
        FieldUpdate fieldUpdate = FieldUpdate.newBuilder()
                .setFieldPath("pageViews")
                .setNewValue(Value.newBuilder().setNumberValue(105).build())
                .setOperation(UpdateOperation.SET)
                .build();

        testIncrementalSync = IncrementalSync.newBuilder()
                .addFieldUpdates(fieldUpdate)
                .build();
    }

    @Nested
    @DisplayName("基础消息构建测试")
    class BasicMessageConstructionTests {

        @Test
        @DisplayName("Builder 模式构建完整消息")
        void testBuilderPatternConstruction() {
            // When - 构建完整的 UserProfileSyncEvent
            UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
                    .setEventId("event-123")
                    .setUserId("user-456")
                    .setTimestamp(testTimestamp)
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .setMetadata(testMetadata)
                    .build();

            // Then - 验证所有字段
            assertEquals("event-123", event.getEventId());
            assertEquals("user-456", event.getUserId());
            assertTrue(event.hasTimestamp());
            assertEquals(testTimestamp, event.getTimestamp());
            assertEquals(SyncType.FULL_SYNC, event.getSyncType());
            assertTrue(event.hasFullSync());
            assertEquals(testFullSync, event.getFullSync());
            assertTrue(event.hasMetadata());
            assertEquals(testMetadata, event.getMetadata());
        }

        @Test
        @DisplayName("必填字段验证")
        void testRequiredFieldValidation() {
            // When - 创建最小消息
            UserProfileSyncEvent minimalEvent = UserProfileSyncEvent.newBuilder()
                    .setEventId("minimal-event")
                    .setUserId("minimal-user")
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .build();

            // Then - 验证基本字段
            assertNotNull(minimalEvent);
            assertEquals("minimal-event", minimalEvent.getEventId());
            assertEquals("minimal-user", minimalEvent.getUserId());
            assertEquals(SyncType.FULL_SYNC, minimalEvent.getSyncType());
            
            // 可选字段应该有默认值
            assertFalse(minimalEvent.hasTimestamp());
            assertFalse(minimalEvent.hasMetadata());
        }

        @Test
        @DisplayName("默认值处理")
        void testDefaultValues() {
            // When - 创建空消息
            UserProfileSyncEvent emptyEvent = UserProfileSyncEvent.newBuilder().build();

            // Then - 验证默认值
            assertEquals("", emptyEvent.getEventId());
            assertEquals("", emptyEvent.getUserId());
            assertEquals(SyncType.SYNC_TYPE_UNSPECIFIED, emptyEvent.getSyncType());
            assertFalse(emptyEvent.hasTimestamp());
            assertFalse(emptyEvent.hasMetadata());
            assertFalse(emptyEvent.hasFullSync());
            assertFalse(emptyEvent.hasIncrementalSync());
            assertEquals(UserProfileSyncEvent.SyncPayloadCase.SYNCPAYLOAD_NOT_SET, 
                    emptyEvent.getSyncPayloadCase());
        }
    }

    @Nested
    @DisplayName("OneOf 字段处理测试")
    class OneOfFieldHandlingTests {

        @Test
        @DisplayName("FullSync OneOf 字段处理")
        void testFullSyncOneOfHandling() {
            // When - 设置 FullSync
            UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
                    .setEventId("full-sync-event")
                    .setUserId("user-123")
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .build();

            // Then - 验证 OneOf 状态
            assertEquals(UserProfileSyncEvent.SyncPayloadCase.FULL_SYNC, 
                    event.getSyncPayloadCase());
            assertTrue(event.hasFullSync());
            assertFalse(event.hasIncrementalSync());
            assertEquals(testFullSync, event.getFullSync());
            
            // 获取未设置的字段应返回默认实例
            assertEquals(IncrementalSync.getDefaultInstance(), event.getIncrementalSync());
        }

        @Test
        @DisplayName("IncrementalSync OneOf 字段处理")
        void testIncrementalSyncOneOfHandling() {
            // When - 设置 IncrementalSync
            UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
                    .setEventId("incremental-sync-event")
                    .setUserId("user-456")
                    .setSyncType(SyncType.INCREMENTAL_SYNC)
                    .setIncrementalSync(testIncrementalSync)
                    .build();

            // Then - 验证 OneOf 状态
            assertEquals(UserProfileSyncEvent.SyncPayloadCase.INCREMENTAL_SYNC, 
                    event.getSyncPayloadCase());
            assertFalse(event.hasFullSync());
            assertTrue(event.hasIncrementalSync());
            assertEquals(testIncrementalSync, event.getIncrementalSync());
            
            // 获取未设置的字段应返回默认实例
            assertEquals(FullProfileSync.getDefaultInstance(), event.getFullSync());
        }

        @Test
        @DisplayName("OneOf 字段切换测试")
        void testOneOfFieldSwitching() {
            // Given - 先设置 FullSync
            UserProfileSyncEvent.Builder builder = UserProfileSyncEvent.newBuilder()
                    .setEventId("switching-event")
                    .setUserId("user-789")
                    .setFullSync(testFullSync);

            // When - 切换到 IncrementalSync
            UserProfileSyncEvent event = builder
                    .setIncrementalSync(testIncrementalSync)
                    .build();

            // Then - 验证只有最后设置的字段生效
            assertEquals(UserProfileSyncEvent.SyncPayloadCase.INCREMENTAL_SYNC, 
                    event.getSyncPayloadCase());
            assertFalse(event.hasFullSync());
            assertTrue(event.hasIncrementalSync());
            assertEquals(testIncrementalSync, event.getIncrementalSync());
        }
    }

    @Nested
    @DisplayName("SyncType 枚举处理测试")
    class SyncTypeEnumTests {

        @Test
        @DisplayName("所有 SyncType 枚举值测试")
        void testAllSyncTypeValues() {
            // 测试 SYNC_TYPE_UNSPECIFIED
            UserProfileSyncEvent unspecifiedEvent = UserProfileSyncEvent.newBuilder()
                    .setSyncType(SyncType.SYNC_TYPE_UNSPECIFIED)
                    .build();
            assertEquals(SyncType.SYNC_TYPE_UNSPECIFIED, unspecifiedEvent.getSyncType());
            assertEquals(0, unspecifiedEvent.getSyncTypeValue());

            // 测试 FULL_SYNC
            UserProfileSyncEvent fullSyncEvent = UserProfileSyncEvent.newBuilder()
                    .setSyncType(SyncType.FULL_SYNC)
                    .build();
            assertEquals(SyncType.FULL_SYNC, fullSyncEvent.getSyncType());
            assertEquals(1, fullSyncEvent.getSyncTypeValue());

            // 测试 INCREMENTAL_SYNC
            UserProfileSyncEvent incrementalSyncEvent = UserProfileSyncEvent.newBuilder()
                    .setSyncType(SyncType.INCREMENTAL_SYNC)
                    .build();
            assertEquals(SyncType.INCREMENTAL_SYNC, incrementalSyncEvent.getSyncType());
            assertEquals(2, incrementalSyncEvent.getSyncTypeValue());
        }

        @Test
        @DisplayName("SyncType 数值设置测试")
        void testSyncTypeValueSetting() {
            // When - 使用数值设置 SyncType
            UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
                    .setSyncTypeValue(2)  // INCREMENTAL_SYNC 的值
                    .build();

            // Then
            assertEquals(SyncType.INCREMENTAL_SYNC, event.getSyncType());
            assertEquals(2, event.getSyncTypeValue());
        }

        @Test
        @DisplayName("未知 SyncType 值处理")
        void testUnknownSyncTypeValue() {
            // When - 设置未知的枚举值
            UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
                    .setSyncTypeValue(999)  // 未知值
                    .build();

            // Then - 应该返回 UNRECOGNIZED
            assertEquals(SyncType.UNRECOGNIZED, event.getSyncType());
            assertEquals(999, event.getSyncTypeValue());
        }
    }

    @Nested
    @DisplayName("序列化和反序列化测试")
    class SerializationTests {

        @Test
        @DisplayName("完整消息序列化往返测试")
        void testFullMessageSerializationRoundTrip() throws InvalidProtocolBufferException {
            // Given - 创建完整的事件
            UserProfileSyncEvent originalEvent = UserProfileSyncEvent.newBuilder()
                    .setEventId("serialization-test-123")
                    .setUserId("serialization-user-456")
                    .setTimestamp(testTimestamp)
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .setMetadata(testMetadata)
                    .build();

            // When - 序列化然后反序列化
            byte[] serializedData = originalEvent.toByteArray();
            UserProfileSyncEvent deserializedEvent = UserProfileSyncEvent.parseFrom(serializedData);

            // Then - 验证所有字段
            assertEquals(originalEvent.getEventId(), deserializedEvent.getEventId());
            assertEquals(originalEvent.getUserId(), deserializedEvent.getUserId());
            assertEquals(originalEvent.getTimestamp(), deserializedEvent.getTimestamp());
            assertEquals(originalEvent.getSyncType(), deserializedEvent.getSyncType());
            assertEquals(originalEvent.getSyncPayloadCase(), deserializedEvent.getSyncPayloadCase());
            assertEquals(originalEvent.getFullSync(), deserializedEvent.getFullSync());
            assertEquals(originalEvent.getMetadata(), deserializedEvent.getMetadata());
            
            // 验证完整对象相等性
            assertEquals(originalEvent, deserializedEvent);
            assertEquals(originalEvent.hashCode(), deserializedEvent.hashCode());
        }

        @Test
        @DisplayName("增量同步消息序列化测试")
        void testIncrementalSyncSerialization() throws InvalidProtocolBufferException {
            // Given
            UserProfileSyncEvent originalEvent = UserProfileSyncEvent.newBuilder()
                    .setEventId("incremental-serialization-test")
                    .setUserId("incremental-user")
                    .setSyncType(SyncType.INCREMENTAL_SYNC)
                    .setIncrementalSync(testIncrementalSync)
                    .build();

            // When
            byte[] serializedData = originalEvent.toByteArray();
            UserProfileSyncEvent deserializedEvent = UserProfileSyncEvent.parseFrom(serializedData);

            // Then
            assertEquals(originalEvent, deserializedEvent);
            assertEquals(UserProfileSyncEvent.SyncPayloadCase.INCREMENTAL_SYNC, 
                    deserializedEvent.getSyncPayloadCase());
            assertEquals(originalEvent.getIncrementalSync(), deserializedEvent.getIncrementalSync());
        }

        @Test
        @DisplayName("JSON 格式序列化测试")
        void testJsonSerialization() throws InvalidProtocolBufferException {
            // Given
            UserProfileSyncEvent event = UserProfileSyncEvent.newBuilder()
                    .setEventId("json-test-event")
                    .setUserId("json-test-user")
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .build();

            // When - 转换为 JSON 然后解析回来
            String jsonString = com.google.protobuf.util.JsonFormat.printer().print(event);
            UserProfileSyncEvent.Builder builder = UserProfileSyncEvent.newBuilder();
            com.google.protobuf.util.JsonFormat.parser().merge(jsonString, builder);
            UserProfileSyncEvent parsedEvent = builder.build();

            // Then
            assertEquals(event.getEventId(), parsedEvent.getEventId());
            assertEquals(event.getUserId(), parsedEvent.getUserId());
            assertEquals(event.getSyncType(), parsedEvent.getSyncType());
            assertEquals(event.getSyncPayloadCase(), parsedEvent.getSyncPayloadCase());
        }

        @Test
        @DisplayName("序列化异常处理测试")
        void testSerializationExceptionHandling() {
            // 测试无效的 protobuf 数据
            byte[] invalidData = {(byte)0x08, (byte)0x96, (byte)0x01, (byte)0x12, (byte)0x04};  // 不完整的数据
            
            assertThrows(InvalidProtocolBufferException.class, () -> {
                UserProfileSyncEvent.parseFrom(invalidData);
            });

            // 测试空数据
            assertDoesNotThrow(() -> {
                UserProfileSyncEvent event = UserProfileSyncEvent.parseFrom(new byte[0]);
                assertEquals(UserProfileSyncEvent.getDefaultInstance(), event);
            });
        }
    }

    @Nested
    @DisplayName("边界条件和异常测试")
    class EdgeCasesAndExceptionTests {

        @Test
        @DisplayName("字符串字段边界测试")
        void testStringFieldBoundaries() {
            // 测试空字符串
            UserProfileSyncEvent emptyStringEvent = UserProfileSyncEvent.newBuilder()
                    .setEventId("")
                    .setUserId("")
                    .build();
            
            assertEquals("", emptyStringEvent.getEventId());
            assertEquals("", emptyStringEvent.getUserId());

            // 测试长字符串
            String longString = "a".repeat(10000);
            UserProfileSyncEvent longStringEvent = UserProfileSyncEvent.newBuilder()
                    .setEventId(longString)
                    .setUserId(longString)
                    .build();
            
            assertEquals(longString, longStringEvent.getEventId());
            assertEquals(longString, longStringEvent.getUserId());
        }

        @Test
        @DisplayName("时间戳边界测试")
        void testTimestampBoundaries() {
            // 测试极值时间戳
            Timestamp minTimestamp = Timestamp.newBuilder()
                    .setSeconds(Timestamp.getDefaultInstance().getSeconds())
                    .setNanos(0)
                    .build();

            Timestamp maxTimestamp = Timestamp.newBuilder()
                    .setSeconds(253402300799L)  // 9999-12-31T23:59:59Z
                    .setNanos(999999999)
                    .build();

            // 最小值测试
            UserProfileSyncEvent minEvent = UserProfileSyncEvent.newBuilder()
                    .setTimestamp(minTimestamp)
                    .build();
            assertEquals(minTimestamp, minEvent.getTimestamp());

            // 最大值测试
            UserProfileSyncEvent maxEvent = UserProfileSyncEvent.newBuilder()
                    .setTimestamp(maxTimestamp)
                    .build();
            assertEquals(maxTimestamp, maxEvent.getTimestamp());
        }

        @Test
        @DisplayName("Builder 重复使用测试")
        void testBuilderReuse() {
            // Given
            UserProfileSyncEvent.Builder builder = UserProfileSyncEvent.newBuilder()
                    .setEventId("reuse-test")
                    .setUserId("reuse-user");

            // When - 第一次构建
            UserProfileSyncEvent event1 = builder
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .build();

            // When - 修改并重新构建
            UserProfileSyncEvent event2 = builder
                    .setSyncType(SyncType.INCREMENTAL_SYNC)
                    .setIncrementalSync(testIncrementalSync)
                    .build();

            // Then - 两个事件应该不同
            assertNotEquals(event1, event2);
            assertEquals(SyncType.FULL_SYNC, event1.getSyncType());
            assertEquals(SyncType.INCREMENTAL_SYNC, event2.getSyncType());
        }

        @Test
        @DisplayName("对象相等性和哈希码测试")
        void testEqualityAndHashCode() {
            // Given - 创建两个相同的事件
            UserProfileSyncEvent event1 = UserProfileSyncEvent.newBuilder()
                    .setEventId("equality-test")
                    .setUserId("equality-user")
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .build();

            UserProfileSyncEvent event2 = UserProfileSyncEvent.newBuilder()
                    .setEventId("equality-test")
                    .setUserId("equality-user")
                    .setSyncType(SyncType.FULL_SYNC)
                    .setFullSync(testFullSync)
                    .build();

            // Then - 验证相等性
            assertEquals(event1, event2);
            assertEquals(event1.hashCode(), event2.hashCode());
            assertEquals(event1.toString(), event2.toString());

            // 自身相等
            assertEquals(event1, event1);
            
            // 与 null 不相等
            assertNotEquals(event1, null);
            
            // 与不同类型不相等
            assertNotEquals(event1, "string");
        }
    }
}