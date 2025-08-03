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
public class CleanupFailedEvent extends BaseEvent {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 异常类型
     */
    private String exceptionType;

    /**
     * 错误堆栈（简化版，用于监控）
     */
    private String errorStack;

    /**
     * 失败阶段
     */
    private CleanupPhase failurePhase;

    /**
     * 任务开始时间
     */
    private Instant startTime;

    /**
     * 失败时间
     */
    private Instant failureTime;

    /**
     * 已处理的记录数（失败前）
     */
    private Integer processedCount;

    /**
     * 失败时的批次号
     */
    private Integer failedBatchNumber;

    /**
     * 错误分类
     */
    private ErrorCategory errorCategory;

    /**
     * 是否可重试
     */
    private Boolean retryable;

    /**
     * 重试次数
     */
    private Integer attemptNumber;

    /**
     * 失败的具体原因码
     */
    private String failureCode;

    @Override
    public String getDisplayName() {
        return "清理任务失败";
    }

    @Override
    public String getEventType() {
        return "CLEANUP_FAILED";
    }

    /**
     * 构建器的便利方法
     */
    public static CleanupFailedEventBuilder defaultBuilder() {
        return CleanupFailedEvent.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .eventType("CLEANUP_FAILED")
                .sourceService("profile-service")
                .version("1.0");
    }

    /**
     * 从异常创建失败事件
     */
    public static CleanupFailedEvent fromException(String taskId, Exception exception) {
        return defaultBuilder()
                .taskId(taskId)
                .errorMessage(exception.getMessage())
                .exceptionType(exception.getClass().getSimpleName())
                .errorStack(getSimplifiedStackTrace(exception))
                .failureTime(Instant.now())
                .errorCategory(categorizeException(exception))
                .retryable(isRetryableException(exception))
                .attemptNumber(1)
                .failureCode(generateFailureCode(exception))
                .build();
    }

    /**
     * 详细失败事件创建
     */
    public static CleanupFailedEvent detailed(String taskId,
                                              Exception exception,
                                              CleanupPhase phase,
                                              Integer processedCount,
                                              Instant startTime) {
        return defaultBuilder()
                .taskId(taskId)
                .errorMessage(exception.getMessage())
                .exceptionType(exception.getClass().getSimpleName())
                .failurePhase(phase)
                .startTime(startTime)
                .failureTime(Instant.now())
                .processedCount(processedCount)
                .errorCategory(categorizeException(exception))
                .retryable(isRetryableException(exception))
                .build();
    }

    // 辅助方法
    private static String getSimplifiedStackTrace(Exception e) {
        return e.getStackTrace().length > 0 ?
                e.getStackTrace()[0].toString() : "No stack trace available";
    }

    private static ErrorCategory categorizeException(Exception e) {
        String message = e.getMessage().toLowerCase();
        if (message.contains("timeout")) return ErrorCategory.TIMEOUT;
        if (message.contains("lock")) return ErrorCategory.LOCK_CONTENTION;
        if (message.contains("database") || message.contains("sql")) return ErrorCategory.DATABASE_ERROR;
        if (message.contains("redis")) return ErrorCategory.CACHE_ERROR;
        return ErrorCategory.UNKNOWN;
    }

    private static boolean isRetryableException(Exception e) {
        // 根据异常类型判断是否可重试
        return !(e instanceof IllegalArgumentException ||
                e instanceof SecurityException);
    }

    private static String generateFailureCode(Exception e) {
        return "ERR_" + e.getClass().getSimpleName().toUpperCase();
    }

    /**
     * 错误分类枚举
     */
    public enum ErrorCategory {
        TIMEOUT("超时错误"),
        LOCK_CONTENTION("锁竞争"),
        DATABASE_ERROR("数据库错误"),
        CACHE_ERROR("缓存错误"),
        NETWORK_ERROR("网络错误"),
        CONFIGURATION_ERROR("配置错误"),
        RESOURCE_EXHAUSTION("资源耗尽"),
        UNKNOWN("未知错误");

        private final String description;

        ErrorCategory(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }


    /**
     * 清理阶段枚举
     */
    public enum CleanupPhase {
        INITIALIZATION("初始化"),
        LOCK_ACQUISITION("获取锁"),
        DATA_SCANNING("数据扫描"),
        DATA_DELETION("数据删除"),
        COUNTER_UPDATE("计数器更新"),
        LOCK_RELEASE("释放锁"),
        COMPLETION("完成");

        private final String description;

        CleanupPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
