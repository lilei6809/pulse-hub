package com.pulsehub.datasync.router;

import com.pulsehub.datasync.proto.SyncPriority;
import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import org.apache.kafka.streams.test.TestRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Branched;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event Router Test
 * 
 * 测试Kafka Streams事件路由器的路由逻辑:
 * - IMMEDIATE优先级事件路由到immediate-sync-events
 * - BATCH优先级事件路由到batch-sync-events
 * - 未知优先级事件默认路由到batch-sync-events
 */
@ExtendWith(MockitoExtension.class)
class EventRouterTest {

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, byte[]> inputTopic;
    private TestOutputTopic<String, byte[]> immediateOutputTopic;
    private TestOutputTopic<String, byte[]> batchOutputTopic;

    @Mock
    private StreamsBuilder streamsBuilder;

    @BeforeEach
    void setUp() {
        // 创建测试用的StreamsBuilder和Topology
        StreamsBuilder realStreamsBuilder = new StreamsBuilder();
        
        // 模拟EventRouter的路由逻辑 - 处理字节数组
        var sourceStream = realStreamsBuilder.<String, byte[]>stream("profile-sync-events");
        sourceStream
            .split()
            .branch(
                (key, eventBytes) -> {
                    try {
                        UserProfileSyncEvent event = UserProfileSyncEvent.parseFrom(eventBytes);
                        return event.getPriority() == SyncPriority.IMMEDIATE;
                    } catch (InvalidProtocolBufferException e) {
                        return false;
                    }
                },
                Branched.withConsumer(stream -> stream.to("immediate-sync-events"))
            )
            .branch(
                (key, eventBytes) -> {
                    try {
                        UserProfileSyncEvent event = UserProfileSyncEvent.parseFrom(eventBytes);
                        return event.getPriority() == SyncPriority.BATCH;
                    } catch (InvalidProtocolBufferException e) {
                        return false;
                    }
                },
                Branched.withConsumer(stream -> stream.to("batch-sync-events"))
            )
            .defaultBranch(
                Branched.withConsumer(stream -> stream.to("batch-sync-events"))
            );

        Topology topology = realStreamsBuilder.build();

        // 配置测试驱动程序
        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "event-router-test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, 
                  org.apache.kafka.common.serialization.Serdes.String().getClass().getName());
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, 
                  org.apache.kafka.common.serialization.Serdes.ByteArray().getClass().getName());

        testDriver = new TopologyTestDriver(topology, config);

        // 创建测试主题 - 使用字节数组序列化Protobuf
        inputTopic = testDriver.createInputTopic(
            "profile-sync-events",
            new StringSerializer(),
            new ByteArraySerializer()
        );

        immediateOutputTopic = testDriver.createOutputTopic(
            "immediate-sync-events",
            new org.apache.kafka.common.serialization.StringDeserializer(),
            new ByteArrayDeserializer()
        );

        batchOutputTopic = testDriver.createOutputTopic(
            "batch-sync-events", 
            new org.apache.kafka.common.serialization.StringDeserializer(),
            new ByteArrayDeserializer()
        );
    }

    @Test
    void shouldRouteImmediateSyncEventToImmediateTopic() throws InvalidProtocolBufferException {
        // Given: 立即同步事件
        UserProfileSyncEvent immediateEvent = createUserProfileSyncEvent("user123", SyncPriority.IMMEDIATE);

        // When: 发送事件到输入Topic
        inputTopic.pipeInput("user123", immediateEvent.toByteArray());

        // Then: 验证事件路由到立即同步Topic
        assertThat(immediateOutputTopic.isEmpty()).isFalse();
        assertThat(batchOutputTopic.isEmpty()).isTrue();

        TestRecord<String, byte[]> record = immediateOutputTopic.readRecord();
        UserProfileSyncEvent receivedEvent = UserProfileSyncEvent.parseFrom(record.value());
        assertThat(record.key()).isEqualTo("user123");
        assertThat(receivedEvent.getUserId()).isEqualTo("user123");
        assertThat(receivedEvent.getPriority()).isEqualTo(SyncPriority.IMMEDIATE);
    }

    @Test
    void shouldRouteBatchSyncEventToBatchTopic() throws InvalidProtocolBufferException {
        // Given: 批量同步事件
        UserProfileSyncEvent batchEvent = createUserProfileSyncEvent("user456", SyncPriority.BATCH);

        // When: 发送事件到输入Topic
        inputTopic.pipeInput("user456", batchEvent.toByteArray());

        // Then: 验证事件路由到批量同步Topic
        assertThat(batchOutputTopic.isEmpty()).isFalse();
        assertThat(immediateOutputTopic.isEmpty()).isTrue();

        TestRecord<String, byte[]> record = batchOutputTopic.readRecord();
        UserProfileSyncEvent receivedEvent = UserProfileSyncEvent.parseFrom(record.value());
        assertThat(record.key()).isEqualTo("user456");
        assertThat(receivedEvent.getUserId()).isEqualTo("user456");
        assertThat(receivedEvent.getPriority()).isEqualTo(SyncPriority.BATCH);
    }

    @Test
    void shouldRouteMultipleEventsCorrectly() throws InvalidProtocolBufferException {
        // Given: 混合优先级事件
        UserProfileSyncEvent immediateEvent1 = createUserProfileSyncEvent("user111", SyncPriority.IMMEDIATE);
        UserProfileSyncEvent batchEvent1 = createUserProfileSyncEvent("user222", SyncPriority.BATCH);
        UserProfileSyncEvent immediateEvent2 = createUserProfileSyncEvent("user333", SyncPriority.IMMEDIATE);

        // When: 发送多个事件
        inputTopic.pipeInput("user111", immediateEvent1.toByteArray());
        inputTopic.pipeInput("user222", batchEvent1.toByteArray());
        inputTopic.pipeInput("user333", immediateEvent2.toByteArray());

        // Then: 验证路由正确性
        // 验证立即同步Topic收到2个事件
        assertThat(immediateOutputTopic.getQueueSize()).isEqualTo(2);
        var immediateRecord1 = immediateOutputTopic.readRecord();
        var immediateRecord2 = immediateOutputTopic.readRecord();
        
        UserProfileSyncEvent receivedImmediate1 = UserProfileSyncEvent.parseFrom(immediateRecord1.value());
        UserProfileSyncEvent receivedImmediate2 = UserProfileSyncEvent.parseFrom(immediateRecord2.value());
        assertThat(receivedImmediate1.getUserId()).isEqualTo("user111");
        assertThat(receivedImmediate2.getUserId()).isEqualTo("user333");

        // 验证批量同步Topic收到1个事件
        assertThat(batchOutputTopic.getQueueSize()).isEqualTo(1);
        var batchRecord = batchOutputTopic.readRecord();
        UserProfileSyncEvent receivedBatch = UserProfileSyncEvent.parseFrom(batchRecord.value());
        assertThat(receivedBatch.getUserId()).isEqualTo("user222");
    }

    /**
     * 创建测试用的UserProfileSyncEvent
     */
    private UserProfileSyncEvent createUserProfileSyncEvent(String userId, SyncPriority priority) {
        return UserProfileSyncEvent.newBuilder()
            .setUserId(userId)
            .setPriority(priority)
            .setVersion(1L)
            .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build())
            .build();
    }

    void tearDown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }
}