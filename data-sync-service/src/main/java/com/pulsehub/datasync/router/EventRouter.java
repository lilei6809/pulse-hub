package com.pulsehub.datasync.router;

import com.pulsehub.datasync.proto.SyncPriority;
import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Kafka Streams Event Router
 * 
 * 负责将用户资料同步事件按照优先级路由到不同的Topic:
 * - IMMEDIATE priority → immediate-sync-events (高优先级)
 * - BATCH priority → batch-sync-events (低优先级)
 * 
 * 特性:
 * - 基于Kafka Streams实现声明式路由
 * - 支持exactly_once_v2语义，避免重复路由
 * - userId作为分区键，保证同用户事件的顺序性
 * - 实时路由，无缓存延迟
 */
@Slf4j
@Component
public class EventRouter {

    @Autowired
    private StreamsBuilder streamsBuilder;

    @Value("${sync.topics.profile-sync}")
    private String sourceTopicName;

    @Value("${sync.topics.immediate-sync}")
    private String immediateSyncTopicName;

    @Value("${sync.topics.batch-sync}")
    private String batchSyncTopicName;

    /**
     * 构建Kafka Streams拓扑结构
     * 
     * 路由逻辑:
     * 1. 监听profile-sync-events Topic
     * 2. 根据SyncPriority枚举值进行分支路由
     * 3. 使用userId作为分区键确保同用户事件顺序
     */
    @PostConstruct
    public void buildTopology() {
        log.info("Building event router topology: {} → {}, {}", 
                sourceTopicName, immediateSyncTopicName, batchSyncTopicName);

        // 创建源数据流 - 从profile-sync-events读取
        KStream<String, UserProfileSyncEvent> sourceStream = streamsBuilder
                .stream(sourceTopicName);

        // 分支路由 - 按优先级分发到不同Topic
        sourceStream
                .split()
                // 立即同步分支 - 关键业务数据
                .branch(
                        (userId, event) -> isImmediateSync(event),
                        Branched.withConsumer(immediateStream -> {
                            immediateStream.to(immediateSyncTopicName);

                        })
                )
                // 批量同步分支 - 普通数据
                .branch(
                        (userId, event) -> isBatchSync(event),
                        Branched.withConsumer(batchStream -> {
                            batchStream.to(batchSyncTopicName);

                        })
                )
                // 默认分支 - 处理未知优先级
                .defaultBranch(
                        Branched.withConsumer(defaultStream -> {
                            defaultStream.foreach((userId, event) -> 
                                log.warn("Unknown sync priority: {} for user: {}, routing to batch", 
                                        event.getPriority(), userId)
                            );
                            defaultStream.to(batchSyncTopicName);
                        })
                );

        log.info("Event router topology built successfully");
    }

    /**
     * 判断是否为立即同步事件
     * 
     * @param event 用户资料同步事件
     * @return true if IMMEDIATE priority
     */
    private boolean isImmediateSync(UserProfileSyncEvent event) {
        if (event == null) {
            log.warn("Received null event, treating as batch sync");
            return false;
        }
        return event.getPriority() == SyncPriority.IMMEDIATE;
    }

    /**
     * 判断是否为批量同步事件
     * 
     * @param event 用户资料同步事件
     * @return true if BATCH priority
     */
    private boolean isBatchSync(UserProfileSyncEvent event) {
        if (event == null) {
            log.warn("Received null event, treating as batch sync");
            return true;
        }
        return event.getPriority() == SyncPriority.BATCH;
    }
}