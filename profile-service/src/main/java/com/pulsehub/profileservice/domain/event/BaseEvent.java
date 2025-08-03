package com.pulsehub.profileservice.domain.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 基础事件类 - 所有事件的公共属性
 */
@Data
@SuperBuilder
public class BaseEvent {

    /**
     * 事件唯一标识
     */
    private String eventId;

    /**
     * 事件发生时间
     */
    private Instant timestamp;

    /**
     * 事件类型标识
     */
    private String eventType;

    /**
     * 事件来源服务
     */
    private String sourceService;

    /**
     * 事件版本（用于版本兼容）
     */
    private String version;

    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;

    /**
     * 获取事件的显示名称
     */
    public String getDisplayName() {
        return null;
    }

    /**
     * 判断是否需要审计
     */
    public boolean isAuditRequired() {
        return true; // 默认所有事件都需要审计
    }
}
