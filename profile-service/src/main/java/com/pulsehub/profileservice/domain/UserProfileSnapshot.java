package com.pulsehub.profileservice.domain;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 用户画像快照
 *
 * 【设计目的】
 * 将静态用户画像(StaticUserProfile)和动态用户画像(DynamicUserProfile)聚合为统一视图，
 * 为下游系统和API提供完整的用户画像数据。
 *
 * 【架构优势】
 * 1. 统一接口：下游系统无需关心数据存储的物理分布
 * 2. 性能优化：可以选择性加载需要的数据字段
 * 3. 版本控制：聚合数据的版本管理和向后兼容
 * 4. 缓存友好：可以整体缓存或部分缓存
 *
 * 【使用场景】
 * - API响应：为前端和移动端提供用户画像数据
 * - 消息传递：作为Kafka消息的载体
 * - 报表导出：为BI系统提供用户数据
 * - 下游集成：为CRM、营销系统提供数据
 *
 * 【数据来源】
 * - 静态数据：从PostgreSQL的StaticUserProfile获取
 * - 动态数据：从Redis的DynamicUserProfile获取
 * - 计算字段：基于两者数据实时计算得出
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfileSnapshot {

    // ===================================================================
    // 基础标识信息
    // ===================================================================

    /**
     * 用户唯一标识
     */
    @JsonProperty("user_id")
    private String userId;

    /**
     * 数据快照生成时间
     */
    @JsonProperty("snapshot_timestamp")
    private Instant snapshotTimestamp;

    /**
     * 数据版本信息（静态数据版本 + 动态数据更新时间）
     */
    @JsonProperty("data_version")
    private String dataVersion;

    // ===================================================================
    // 静态画像数据（来自PostgreSQL）
    // ===================================================================

    /**
     * 用户注册时间
     */
    @JsonProperty("registration_date")
    private Instant registrationDate;

    /**
     * 用户性别
     */
    @JsonProperty("gender")
    private StaticUserProfile.Gender gender;

    /**
     * 用户来源渠道
     */
    @JsonProperty("source_channel")
    private String sourceChannel;

    /**
     * 用户真实姓名
     */
    @JsonProperty("real_name")
    private String realName;

    /**
     * 用户邮箱
     */
    @JsonProperty("email")
    private String email;

    /**
     * 用户手机号
     */
    @JsonProperty("phone_number")
    private String phoneNumber;

    /**
     * 用户所在城市
     */
    @JsonProperty("city")
    private String city;

    /**
     * 用户年龄段
     */
    @JsonProperty("age_group")
    private StaticUserProfile.AgeGroup ageGroup;

    // ===================================================================
    // 动态画像数据（来自Redis）
    // ===================================================================

    /**
     * 最后活跃时间
     */
    @JsonProperty("last_active_at")
    private Instant lastActiveAt;

    /**
     * 页面浏览总数
     */
    @JsonProperty("page_view_count")
    private Long pageViewCount;

    /**
     * 主要设备分类
     */
    @JsonProperty("device_classification")
    private DeviceClass deviceClassification;

    /**
     * 最近使用的设备类型集合
     */
    @JsonProperty("recent_device_types")
    private Set<DeviceClass> recentDeviceTypes;

    /**
     * 动态画像最后更新时间
     */
    @JsonProperty("dynamic_updated_at")
    private Instant dynamicUpdatedAt;

    /**
     * 动态画像版本号
     */
    @JsonProperty("dynamic_version")
    private Long dynamicVersion;

    // ===================================================================
    // 计算字段（基于静态+动态数据计算）
    // ===================================================================

    /**
     * 用户活跃度等级
     * 基于最后活跃时间计算
     */
    @JsonProperty("activity_level")
    public String getActivityLevel() {
        if (lastActiveAt == null) {
            return "UNKNOWN";
        }

        long hoursInactive = ChronoUnit.HOURS.between(lastActiveAt, Instant.now());

        if (hoursInactive <= 1) {
            return "VERY_ACTIVE";     // 1小时内活跃
        } else if (hoursInactive <= 24) {
            return "ACTIVE";          // 24小时内活跃
        } else if (hoursInactive <= 168) {  // 7天
            return "LESS_ACTIVE";     // 7天内活跃
        } else {
            return "INACTIVE";        // 7天以上未活跃
        }
    }

    /**
     * 用户生命周期阶段
     * 基于注册时间和活跃度计算
     */
    @JsonProperty("lifecycle_stage")
    public String getLifecycleStage() {
        if (registrationDate == null) {
            return "UNKNOWN";
        }

        long registrationDays = ChronoUnit.DAYS.between(registrationDate, Instant.now());
        String activityLevel = getActivityLevel();

        if (registrationDays <= 7) {
            return "NEW_USER";        // 新用户（7天内注册）
        } else if (registrationDays <= 30 && "VERY_ACTIVE".equals(activityLevel)) {
            return "GROWING_USER";    // 成长用户（30天内注册且活跃）
        } else if ("VERY_ACTIVE".equals(activityLevel) || "ACTIVE".equals(activityLevel)) {
            return "MATURE_USER";     // 成熟用户（长期注册且活跃）
        } else if ("LESS_ACTIVE".equals(activityLevel)) {
            return "AT_RISK_USER";    // 流失风险用户
        } else {
            return "DORMANT_USER";    // 沉睡用户
        }
    }

    /**
     * 用户价值评分
     * 基于活跃度、使用时长、设备多样性等计算
     */
    @JsonProperty("value_score")
    public int getValueScore() {
        int score = 0;

        // 基础分数：注册即有价值
        score += 20;

        // 活跃度加分
        String activityLevel = getActivityLevel();
        switch (activityLevel) {
            case "VERY_ACTIVE": score += 40; break;
            case "ACTIVE": score += 30; break;
            case "LESS_ACTIVE": score += 10; break;
            default: break;
        }

        // 页面浏览数加分
        if (pageViewCount != null) {
            if (pageViewCount > 1000) score += 20;
            else if (pageViewCount > 100) score += 15;
            else if (pageViewCount > 10) score += 10;
            else if (pageViewCount > 0) score += 5;
        }

        // 设备多样性加分（多设备用户粘性更高）
        if (recentDeviceTypes != null && recentDeviceTypes.size() > 1) {
            score += recentDeviceTypes.size() * 5;
        }

        // 信息完整度加分
        int profileCompleteness = calculateProfileCompleteness();
        score += profileCompleteness / 5; // 最多20分

        return Math.min(score, 100); // 最高100分
    }

    /**
     * 用户信息完整度
     * 计算用户填写的信息完整程度
     */
    @JsonProperty("profile_completeness")
    public int getProfileCompleteness() {
        return calculateProfileCompleteness();
    }

    private int calculateProfileCompleteness() {
        int score = 0;
        int totalFields = 7;

        if (realName != null && !realName.trim().isEmpty()) score++;
        if (email != null && !email.trim().isEmpty()) score++;
        if (phoneNumber != null && !phoneNumber.trim().isEmpty()) score++;
        if (city != null && !city.trim().isEmpty()) score++;
        if (gender != null && gender != StaticUserProfile.Gender.UNKNOWN) score++;
        if (ageGroup != null && ageGroup != StaticUserProfile.AgeGroup.UNKNOWN) score++;
        if (sourceChannel != null && !sourceChannel.trim().isEmpty()) score++;

        return (score * 100) / totalFields;
    }

    /**
     * 是否为新用户
     */
    @JsonProperty("is_new_user")
    public boolean isNewUser() {
        return "NEW_USER".equals(getLifecycleStage());
    }

    /**
     * 是否为活跃用户
     */
    @JsonProperty("is_active_user")
    public boolean isActiveUser() {
        String activityLevel = getActivityLevel();
        return "VERY_ACTIVE".equals(activityLevel) || "ACTIVE".equals(activityLevel);
    }

    /**
     * 是否为高价值用户
     */
    @JsonProperty("is_high_value_user")
    public boolean isHighValueUser() {
        return getValueScore() >= 80;
    }

    // ===================================================================
    // 静态工厂方法
    // ===================================================================

    /**
     * 从静态和动态画像数据创建快照
     *
     * @param staticProfile 静态用户画像
     * @param dynamicProfile 动态用户画像
     * @return 用户画像快照
     */
    public static UserProfileSnapshot from(StaticUserProfile staticProfile, DynamicUserProfile dynamicProfile) {
        UserProfileSnapshotBuilder builder = UserProfileSnapshot.builder()
                .snapshotTimestamp(Instant.now());

        // 设置基础信息
        if (staticProfile != null) {
            builder.userId(staticProfile.getUserId())
                    .registrationDate(staticProfile.getRegistrationDate())
                    .gender(staticProfile.getGender())
                    .sourceChannel(staticProfile.getSourceChannel())
                    .realName(staticProfile.getRealName())
                    .email(staticProfile.getEmail())
                    .phoneNumber(staticProfile.getPhoneNumber())
                    .city(staticProfile.getCity())
                    .ageGroup(staticProfile.getAgeGroup());
        }

        // 设置动态信息
        if (dynamicProfile != null) {
            builder
                    .lastActiveAt(dynamicProfile.getLastActiveAt())
                    .pageViewCount(dynamicProfile.getPageViewCount())
                    .deviceClassification(dynamicProfile.getDeviceClassification())
                    .recentDeviceTypes(dynamicProfile.getRecentDeviceTypes())
                    .dynamicUpdatedAt(dynamicProfile.getUpdatedAt())
                    .dynamicVersion(dynamicProfile.getVersion());
        }

        // 生成数据版本信息
        // S_version_D_version
        String dataVersion = generateDataVersion(staticProfile, dynamicProfile);
        builder.dataVersion(dataVersion);

        return builder.build();
    }

    /**
     * 从静态画像创建快照（仅包含静态数据）
     */
    public static UserProfileSnapshot fromStatic(StaticUserProfile staticProfile) {
        return from(staticProfile, null);
    }

    /**
     * 从动态画像创建快照（仅包含动态数据）
     */
    public static UserProfileSnapshot fromDynamic(DynamicUserProfile dynamicProfile) {
        return from(null, dynamicProfile);
    }

    /**
     * 生成数据版本标识
     */
    private static String generateDataVersion(StaticUserProfile staticProfile, DynamicUserProfile dynamicProfile) {
        StringBuilder version = new StringBuilder();

        if (staticProfile != null && staticProfile.getVersion() != null) {
            version.append("S").append(staticProfile.getVersion());
        }

        if (dynamicProfile != null && dynamicProfile.getUpdatedAt() != null) {
            if (version.length() > 0) {
                version.append("_");
            }
            version.append("D").append(dynamicProfile.getUpdatedAt().getEpochSecond());
        }

        return version.toString();
    }

    // ===================================================================
    // 业务方法
    // ===================================================================

    /**
     * 检查数据是否完整
     *
     * @return 是否包含必要的数据
     */
    public boolean isDataComplete() {
        return userId != null &&
                (registrationDate != null || lastActiveAt != null);
    }

    /**
     * 获取数据新鲜度（分钟）
     *
     * @return 快照生成距离现在的分钟数
     */
    public long getDataFreshness() {
        if (snapshotTimestamp == null) {
            return Long.MAX_VALUE;
        }
        return ChronoUnit.MINUTES.between(snapshotTimestamp, Instant.now());
    }

    /**
     * 检查是否需要刷新数据
     *
     * @param maxAgeMinutes 最大允许的数据年龄（分钟）
     * @return 是否需要刷新
     */
    public boolean needsRefresh(long maxAgeMinutes) {
        return getDataFreshness() > maxAgeMinutes;
    }
}