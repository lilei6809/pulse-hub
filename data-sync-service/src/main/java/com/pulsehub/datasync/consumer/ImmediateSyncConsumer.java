package com.pulsehub.datasync.consumer;

import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import com.pulsehub.datasync.service.MongoProfileUpdater;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Immediate Sync Consumer
 * 
 * 负责处理高优先级的立即同步事件:
 * - 关键业务数据 (status, permissions, vip_level)
 * - 单条消息处理，确保低延迟
 * - 3次重试后降级到批量队列
 * - 完整的监控指标和告警
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImmediateSyncConsumer {

    private final MongoProfileUpdater mongoUpdater;
    private final KafkaTemplate<String, UserProfileSyncEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${sync.topics.batch-sync}")
    private String batchSyncTopicName;

    private Counter immediateSuccessCounter;
    private Counter immediateFallbackCounter;

    /**
     * 处理立即同步事件
     * 
     * 配置说明:
     * - topics: immediate-sync-events
     * - groupId: immediate-sync-group  
     * - concurrency: 2 (配置文件中设置)
     * - 单条消息处理 (max-poll-records: 1)
     */
    @Timed(name = "immediate.sync.duration", description = "立即同步处理时间")
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @KafkaListener(
            topics = "${sync.topics.immediate-sync}",
            groupId = "${spring.kafka.consumer.immediate-sync-group.group-id}",
            containerFactory = "immediateKafkaListenerContainerFactory"
    )
    public void handleImmediateSync(UserProfileSyncEvent event) {
        try {
            log.info("Processing IMMEDIATE sync for user: {}, version: {}", 
                    event.getUserId(), event.getVersion());

            // 立即更新MongoDB
            boolean success = mongoUpdater.updateProfile(event);
            
            if (success) {
                getImmediateSuccessCounter().increment();
                log.info("Successfully processed IMMEDIATE sync for user: {}, version: {}", 
                        event.getUserId(), event.getVersion());
            } else {
                // MongoDB更新失败，抛出异常触发重试
                throw new ImmediateSyncException(
                        "MongoDB update failed for user: " + event.getUserId());
            }

        } catch (Exception e) {
            log.error("Failed to process IMMEDIATE sync for user: {}, version: {}", 
                    event.getUserId(), event.getVersion(), e);
            throw new ImmediateSyncException("Immediate sync processing failed", e);
        }
    }

    /**
     * 重试失败后的降级处理
     * 
     * 将立即同步事件降级为批量同步事件，转发到批量队列
     */
    @Recover
    public void fallbackToBatch(ImmediateSyncException ex, UserProfileSyncEvent event) {
        log.warn("IMMEDIATE sync failed after retries for user: {}, falling back to BATCH sync", 
                event.getUserId());

        try {
            // 创建批量同步事件 (降级)
            UserProfileSyncEvent batchEvent = event.toBuilder()
                    .setPriority(com.pulsehub.datasync.proto.SyncPriority.BATCH)
                    .build();

            // 转发到批量同步队列
            kafkaTemplate.send(batchSyncTopicName, event.getUserId(), batchEvent);
            
            getImmediateFallbackCounter().increment();
            
            log.info("Successfully fallback IMMEDIATE sync to BATCH for user: {}", 
                    event.getUserId());

        } catch (Exception e) {
            log.error("CRITICAL: Failed to fallback IMMEDIATE sync to BATCH for user: {}. " +
                    "Data may be lost!", event.getUserId(), e);
            
            // TODO: 发送告警通知 - 这是关键业务数据丢失的风险
            // alertService.sendCriticalAlert("Immediate sync fallback failed: " + event.getUserId());
        }
    }

    /**
     * 获取立即同步成功计数器 (lazy initialization)
     */
    private Counter getImmediateSuccessCounter() {
        if (immediateSuccessCounter == null) {
            immediateSuccessCounter = Counter.builder("immediate.sync.success")
                    .description("立即同步成功次数")
                    .register(meterRegistry);
        }
        return immediateSuccessCounter;
    }

    /**
     * 获取立即同步降级计数器 (lazy initialization)
     */
    private Counter getImmediateFallbackCounter() {
        if (immediateFallbackCounter == null) {
            immediateFallbackCounter = Counter.builder("immediate.sync.fallback")
                    .description("立即同步降级次数")
                    .register(meterRegistry);
        }
        return immediateFallbackCounter;
    }

    /**
     * 立即同步异常
     */
    public static class ImmediateSyncException extends RuntimeException {
        public ImmediateSyncException(String message) {
            super(message);
        }

        public ImmediateSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}