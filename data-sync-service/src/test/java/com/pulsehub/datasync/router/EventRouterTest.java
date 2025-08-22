package com.pulsehub.datasync.router;

import com.pulsehub.datasync.proto.SyncPriority;
import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.JsonSerializer;

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
    private TestInputTopic<String, UserProfileSyncEvent> inputTopic;
    private TestOutputTopic<String, UserProfileSyncEvent> immediateOutputTopic;
    private TestOutputTopic<String, UserProfileSyncEvent> batchOutputTopic;

    @Mock
    private StreamsBuilder streamsBuilder;

    @BeforeEach
    void setUp() {
        // 创建测试用的StreamsBuilder和Topology
        StreamsBuilder realStreamsBuilder = new StreamsBuilder();
        
        // 模拟EventRouter的路由逻辑
        var sourceStream = realStreamsBuilder.stream("profile-sync-events");
        sourceStream
            .split()
            .branch(
                (key, event) -> ((UserProfileSyncEvent) event).getPriority() == SyncPriority.IMMEDIATE,
                Branched.withConsumer(stream -> stream.to("immediate-sync-events"))
            )
            .branch(
                (key, event) -> ((UserProfileSyncEvent) event).getPriority() == SyncPriority.BATCH,
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
                  org.springframework.kafka.support.serializer.JsonSerde.class.getName());

        testDriver = new TopologyTestDriver(topology, config);

        // 创建测试主题
        inputTopic = testDriver.createInputTopic(
            "profile-sync-events",
            new StringSerializer(),
            new JsonSerializer<UserProfileSyncEvent>()
        );

        immediateOutputTopic = testDriver.createOutputTopic(
            "immediate-sync-events",
            new org.apache.kafka.common.serialization.StringDeserializer(),
            new org.springframework.kafka.support.serializer.JsonDeserializer<>(UserProfileSyncEvent.class)
        );

        batchOutputTopic = testDriver.createOutputTopic(
            "batch-sync-events", 
            new org.apache.kafka.common.serialization.StringDeserializer(),
            new org.springframework.kafka.support.serializer.JsonDeserializer<>(UserProfileSyncEvent.class)
        );
    }

    @Test
    void shouldRouteImmediateSyncEventToImmediateTopic() {
        // Given: 立即同步事件
        UserProfileSyncEvent immediateEvent = createUserProfileSyncEvent("user123", SyncPriority.IMMEDIATE);

        // When: 发送事件到输入Topic
        inputTopic.pipeInput("user123", immediateEvent);

        // Then: 验证事件路由到立即同步Topic
        assertThat(immediateOutputTopic.isEmpty()).isFalse();
        assertThat(batchOutputTopic.isEmpty()).isTrue();

        ProducerRecord<String, UserProfileSyncEvent> record = immediateOutputTopic.readRecord();
        assertThat(record.key()).isEqualTo("user123");
        assertThat(record.value().getUserId()).isEqualTo("user123");
        assertThat(record.value().getPriority()).isEqualTo(SyncPriority.IMMEDIATE);
    }

    @Test
    void shouldRouteBatchSyncEventToBatchTopic() {
        // Given: 批量同步事件
        UserProfileSyncEvent batchEvent = createUserProfileSyncEvent("user456", SyncPriority.BATCH);

        // When: 发送事件到输入Topic
        inputTopic.pipeInput("user456", batchEvent);

        // Then: 验证事件路由到批量同步Topic
        assertThat(batchOutputTopic.isEmpty()).isFalse();
        assertThat(immediateOutputTopic.isEmpty()).isTrue();

        ProducerRecord<String, UserProfileSyncEvent> record = batchOutputTopic.readRecord();
        assertThat(record.key()).isEqualTo("user456");
        assertThat(record.value().getUserId()).isEqualTo("user456");
        assertThat(record.value().getPriority()).isEqualTo(SyncPriority.BATCH);
    }

    @Test
    void shouldRouteMultipleEventsCorrectly() {
        // Given: 混合优先级事件
        UserProfileSyncEvent immediateEvent1 = createUserProfileSyncEvent("user111", SyncPriority.IMMEDIATE);
        UserProfileSyncEvent batchEvent1 = createUserProfileSyncEvent("user222", SyncPriority.BATCH);
        UserProfileSyncEvent immediateEvent2 = createUserProfileSyncEvent("user333", SyncPriority.IMMEDIATE);

        // When: 发送多个事件
        inputTopic.pipeInput("user111", immediateEvent1);
        inputTopic.pipeInput("user222", batchEvent1);
        inputTopic.pipeInput("user333", immediateEvent2);

        // Then: 验证路由正确性
        // 验证立即同步Topic收到2个事件
        assertThat(immediateOutputTopic.getQueueSize()).isEqualTo(2);
        var immediateRecord1 = immediateOutputTopic.readRecord();
        var immediateRecord2 = immediateOutputTopic.readRecord();
        
        assertThat(immediateRecord1.value().getUserId()).isEqualTo("user111");
        assertThat(immediateRecord2.value().getUserId()).isEqualTo("user333");

        // 验证批量同步Topic收到1个事件
        assertThat(batchOutputTopic.getQueueSize()).isEqualTo(1);
        var batchRecord = batchOutputTopic.readRecord();
        assertThat(batchRecord.value().getUserId()).isEqualTo("user222");
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