package com.pulsehub.profileservice.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 动态用户画像模型
 * 
 * 用于存储用户的动态行为数据，包括：
 * - 活跃状态跟踪（最后活跃时间）
 * - 行为统计（页面浏览计数）
 * - 设备分类信息
 * 
 * 设计原则：
 * - 高频更新的数据，适合Redis存储
 * - 支持Kafka序列化传输
 * - 字段可扩展，版本控制
 */
@Data
@Builder
@Component
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicUserProfile implements Serializable {



    private static final long serialVersionUID = 1L;


    /**
     * 用户唯一标识
     * 与静态用户画像保持一致的用户ID
     * 
     * 【重要性】
     * - 数据关联：确保动态和静态画像能正确匹配
     * - 缓存键值：作为Redis缓存的主键
     * - 分布式路由：在微服务间正确传递用户标识
     * - 聚合操作：ProfileAggregationService依赖此字段进行数据聚合
     */
    private String userId;

    /**
     * 最后活跃时间
     * 记录用户最后一次产生行为事件的时间
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant lastActiveAt;

    /**
     * 页面浏览总计数
     * 累计用户的页面访问次数
     */
    @Builder.Default
    private Long pageViewCount = 0L;

    /**
     * 设备分类
     * 根据用户行为推断的主要设备类型
     */
    private DeviceClass deviceClassification;

    /**
     * 最近使用的设备类型集合
     * 记录用户近期使用过的所有设备类型
     */
    @Builder.Default
    private Set<DeviceClass> recentDeviceTypes = new HashSet<>();

    /**
     * 原始设备信息
     * 保留用户上报的原始设备字符串，用于审计和机器学习训练
     * 
     * 【业务价值】
     * - 审计跟踪：保留完整的设备识别历史
     * - 机器学习：为设备分类算法提供训练数据
     * - 问题诊断：当设备分类出现异常时可以回溯原始数据
     */
    private String rawDeviceInput;

    /**
     * 数据版本号
     * 用于并发控制和数据演进
     */
    @Builder.Default
    private Long version = 1L;

    /**
     * 数据最后更新时间
     * 记录画像数据的最后修改时间
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant updatedAt;



    // ==================== 辅助方法 ====================

    /**
     * 增加页面浏览计数
     * 线程安全的计数器增加方法
     */
    public void incrementPageViewCount() {
        if (this.pageViewCount == null) {
            this.pageViewCount = 1L;
        } else {
            this.pageViewCount++;
        }
        updateLastModified();
    }

    /**
     * 增加指定数量的页面浏览计数
     * @param count 要增加的计数
     */
    public void incrementPageViewCount(long count) {
        if (count <= 0) {
            return;
        }
        if (this.pageViewCount == null) {
            this.pageViewCount = count;
        } else {
            this.pageViewCount += count;
        }
        updateLastModified();
    }

    /**
     * 更新最后活跃时间为当前时间
     */
    public void updateLastActiveAt() {
        this.lastActiveAt = Instant.now();
        updateLastModified();
    }

    /**
     * 更新最后活跃时间为指定时间
     * @param activeTime 活跃时间
     */
    public void updateLastActiveAt(Instant activeTime) {
        if (activeTime != null) {
            this.lastActiveAt = activeTime;
            updateLastModified();
        }
    }

    /**
     * 添加设备类型到最近使用设备集合
     * @param deviceClass 设备类型
     */
    public void addRecentDeviceType(DeviceClass deviceClass) {
        if (deviceClass != null) {
            if (this.recentDeviceTypes == null) {
                this.recentDeviceTypes = new HashSet<>();
            }
            this.recentDeviceTypes.add(deviceClass);
            updateLastModified();
        }
    }

    /**
     * 设置主要设备分类
     * 同时将该设备类型添加到最近使用设备集合
     * @param deviceClass 设备分类
     */
    public void setMainDeviceClassification(DeviceClass deviceClass) {
        this.deviceClassification = deviceClass;
        addRecentDeviceType(deviceClass);
    }

    /**
     * 更新设备信息（智能方法）
     * 同时更新分类结果和原始输入，适用于工厂模式创建
     * 
     * @param classified 已分类的设备类型
     * @param rawInput 原始设备输入字符串
     */
    public void updateDeviceInformation(DeviceClass classified, String rawInput) {
        this.deviceClassification = classified;
        this.rawDeviceInput = rawInput;
        addRecentDeviceType(classified);
        updateLastModified();
    }

    /**
     * 从原始设备信息更新（便利方法）
     * 当只有原始输入，需要外部分类时使用
     * 
     * @param rawInput 原始设备输入
     */
    public void updateRawDeviceInput(String rawInput) {
        this.rawDeviceInput = rawInput;
        updateLastModified();
    }

    /**
     * 更新版本号和修改时间
     * 内部方法，用于数据一致性控制
     */
    private void updateLastModified() {
        this.updatedAt = Instant.now();
        if (this.version == null) {
            this.version = 1L;
        } else {
            this.version++;
        }
    }

    /**
     * 检查用户是否在指定时间内活跃
     * @param withinSeconds 时间范围（秒）
     * @return 是否活跃
     */
    public boolean isActiveWithin(long withinSeconds) {
        if (this.lastActiveAt == null) {
            return false;
        }
        return this.lastActiveAt.isAfter(
            Instant.now().minusSeconds(withinSeconds)
        );
    }

    /**
     * 获取用户活跃程度描述
     * @return 活跃程度字符串
     */
    public String getActivityLevel() {
        if (lastActiveAt == null) {
            return "UNKNOWN";
        }
        
        long hoursAgo = java.time.Duration.between(lastActiveAt, Instant.now()).toHours();
        
        if (hoursAgo <= 1) {
            return "VERY_ACTIVE";
        } else if (hoursAgo <= 24) {
            return "ACTIVE";
        } else if (hoursAgo <= 168) { // 7 days
            return "LESS_ACTIVE";
        } else {
            return "INACTIVE";
        }
    }

    public boolean isValid(){
        return userId != null && !userId.trim().isEmpty();
    }

}
