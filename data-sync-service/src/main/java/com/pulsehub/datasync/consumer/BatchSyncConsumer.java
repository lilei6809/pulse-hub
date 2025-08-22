package com.pulsehub.datasync.consumer;

import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import com.pulsehub.datasync.service.MongoProfileUpdater;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Batch Sync Consumer
 * 
 * 负责处理低优先级的批量同步事件:
 * - 行为数据和统计信息
 * - 批量消息处理，追求高吞吐量
 * - 容错性高，重试机制相对宽松
 * - 监控批量处理效率
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchSyncConsumer {

    private final MongoProfileUpdater mongoUpdater;
    private final MeterRegistry meterRegistry;

    private Counter batchSuccessCounter;
    private Counter batchFailureCounter;

    /**
     * 处理批量同步事件
     * 
     * 配置说明:
     * - topics: batch-sync-events
     * - groupId: batch-sync-group
     * - concurrency: 5 (配置文件中设置)
     * - 批量消息处理 (max-poll-records: 10)
     */
    @Timed(value = "batch.sync.duration", description = "批量同步处理时间")
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 2,
            backoff = @Backoff(delay = 2000, multiplier = 1.5)
    )
    @KafkaListener(
            topics = "${sync.topics.batch-sync}",
            groupId = "${spring.kafka.consumer.batch-sync-group.group-id}",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void handleBatchSync(UserProfileSyncEvent event) {
        try {
            log.debug("Processing BATCH sync for user: {}, version: {}", 
                    event.getUserId(), event.getVersion());

            // 批量更新MongoDB (可以容忍一定的失败率)
            boolean success = mongoUpdater.updateProfile(event);
            
            if (success) {
                getBatchSuccessCounter().increment();
                log.debug("Successfully processed BATCH sync for user: {}, version: {}", 
                        event.getUserId(), event.getVersion());
            } else {
                getBatchFailureCounter().increment();
                log.warn("MongoDB update failed for BATCH sync user: {}, version: {}. " +
                        "Will be retried by Kafka.", 
                        event.getUserId(), event.getVersion());
                
                // 对于批量同步，失败不抛出异常，由Kafka自动重试
                // throw new BatchSyncException("MongoDB update failed for user: " + event.getUserId());
            }

        } catch (Exception e) {
            getBatchFailureCounter().increment();
            log.error("Failed to process BATCH sync for user: {}, version: {}. Will retry.", 
                    event.getUserId(), event.getVersion(), e);
            
            // 只有在严重错误时才抛出异常
            if (isCriticalError(e)) {
                throw new BatchSyncException("Critical error in batch sync processing", e);
            }
        }
    }

    /**
     * 判断是否为关键错误
     * 
     * @param e 异常
     * @return true if critical error that requires retry
     */
    private boolean isCriticalError(Exception e) {
        // 连接超时、网络错误等需要重试
        if (e instanceof java.net.SocketTimeoutException ||
            e instanceof java.net.ConnectException ||
            e.getMessage().contains("timeout")) {
            return true;
        }
        
        // 数据格式错误等不需要重试
        if (e instanceof IllegalArgumentException ||
            e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            log.warn("Data format error, skipping retry: {}", e.getMessage());
            return false;
        }
        
        // 默认重试
        return true;
    }

    /**
     * 获取批量同步成功计数器 (lazy initialization)
     */
    private Counter getBatchSuccessCounter() {
        if (batchSuccessCounter == null) {
            batchSuccessCounter = Counter.builder("batch.sync.success")
                    .description("批量同步成功次数")
                    .register(meterRegistry);
        }
        return batchSuccessCounter;
    }

    /**
     * 获取批量同步失败计数器 (lazy initialization)
     */
    private Counter getBatchFailureCounter() {
        if (batchFailureCounter == null) {
            batchFailureCounter = Counter.builder("batch.sync.failure")
                    .description("批量同步失败次数")
                    .register(meterRegistry);
        }
        return batchFailureCounter;
    }

    /**
     * 批量同步异常
     */
    public static class BatchSyncException extends RuntimeException {
        public BatchSyncException(String message) {
            super(message);
        }

        public BatchSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}