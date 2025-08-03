package com.pulsehub.profileservice.domain.event;


import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public class CleanupCompletedEvent extends BaseEvent {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 成功清理的记录数量
     */
    private Integer cleanedCount;

    /**
     * 任务开始时间
     */
    private Instant startTime;

    /**
     * 任务结束时间
     */
    private Instant endTime;

    /**
     * 任务执行持续时间
     */
    private Duration duration;

    /**
     * 清理的数据类型描述
     */
    private String dataType;

    /**
     * 清理策略
     */
    private String cleanupStrategy;

    /**
     * 批次大小
     */
    private Integer batchSize;

    /**
     * 处理的批次数量
     */
    private Integer batchCount;

    /**
     * 跳过的记录数（如果有）
     */
    private Integer skippedCount;

    /**
     * 清理涉及的数据范围（如时间范围）
     */
    private String dataRange;

    private long totalExpiredCount;
    private long totalCandidateCount;

    /**
     * 性能指标
     */
//    private PerformanceMetrics performanceMetrics;

    @Override
    public String getDisplayName() {
        return "清理任务完成";
    }

    @Override
    public String getEventType() {
        return "CLEANUP_COMPLETED";
    }

    /**
     * 构建器的便利方法
     */
    public static CleanupCompletedEventBuilder defaultBuilder() {
        return CleanupCompletedEvent.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .eventType("CLEANUP_COMPLETED")
                .sourceService("profile-service")
                .version("1.0");
    }

    /**
     * 快速创建方法（最小必需信息）
     */
    public static CleanupCompletedEvent of(String taskId, Integer cleanedCount) {
        return defaultBuilder()
                .taskId(taskId)
                .cleanedCount(cleanedCount)
                .endTime(Instant.now())
                .dataType("expired_users")
                .cleanupStrategy("TTL_BASED")
                .build();
    }

    /**
     * 详细创建方法
     */
    public static CleanupCompletedEvent detailed(String taskId,
                                                 Integer cleanedCount,
                                                 Instant startTime,
                                                 String dataRange) {
        Instant endTime = Instant.now();
        return defaultBuilder()
                .taskId(taskId)
                .cleanedCount(cleanedCount)
                .startTime(startTime)
                .endTime(endTime)
                .duration(Duration.between(startTime, endTime))
                .dataRange(dataRange)
                .dataType("expired_users")
                .cleanupStrategy("TTL_BASED")
                .build();
    }
}
