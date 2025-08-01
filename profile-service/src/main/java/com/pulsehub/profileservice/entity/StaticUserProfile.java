package com.pulsehub.profileservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.Instant;

/**
 * 静态用户画像实体
 * 
 * 【设计原则】
 * - 存储用户的静态信息，这些信息很少变化
 * - 一次创建，长期存在
 * - 适合关系型数据库存储（PostgreSQL）
 * 
 * 【字段分类】
 * - 身份信息：userId（主键）
 * - 注册信息：registrationDate, sourceChannel
 * - 基础属性：gender
 * - 审计字段：createdAt, updatedAt
 * 
 * 【与动态画像区别】
 * - 静态：变化频率低，数据稳定，适合SQL查询
 * - 动态：变化频率高，实时性要求，适合NoSQL存储
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "static_user_profiles")
public class StaticUserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识
     * 作为主键，与其他系统保持一致
     */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 50)
    private String userId;

    /**
     * 用户真实姓名
     * 用于用户身份识别和个性化服务
     */
    @Column(name = "real_name", length = 100)
    private String realName;

    /**
     * 用户邮箱地址
     * 用于通信和账户验证，需要保证唯一性
     */
    @Column(name = "email", length = 255, unique = true)
    private String email;

    /**
     * 用户手机号码
     * 用于通信和身份验证
     */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    /**
     * 用户所在城市
     * 用于地域分析和本地化服务
     */
    @Column(name = "city", length = 50)
    private String city;

    /**
     * 用户年龄段
     * 用于用户画像分析和精准营销
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "age_group", length = 20)
    private AgeGroup ageGroup;

    /**
     * 用户注册时间
     * 记录用户首次注册的时间，用于用户生命周期分析
     */
    @Column(name = "registration_date", nullable = false)
    private Instant registrationDate;

    /**
     * 用户性别
     * 用于用户画像分析和个性化推荐
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;

    /**
     * 用户来源渠道
     * 记录用户是从哪个渠道注册的，用于渠道效果分析
     */
    @Column(name = "source_channel", length = 50)
    private String sourceChannel;

    // ===================================================================
    // 审计字段
    // ===================================================================

    /**
     * 记录创建时间
     * 自动生成，不可修改
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 记录最后更新时间
     * 自动更新
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 版本号
     * 用于乐观锁控制和数据版本跟踪
     * 
     * 【作用说明】
     * 1. 乐观锁：防止并发更新时的数据覆盖问题
     * 2. 缓存控制：基于版本号判断缓存数据是否有效
     * 3. 聚合策略：在UserProfileSnapshot中标识静态数据的版本
     * 4. 审计追踪：跟踪数据的变更次数
     * 
     * 【自动管理】
     * - JPA @Version注解自动管理
     * - 每次实体更新时自动递增
     * - 查询时自动加载当前版本号
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * 软删除标记
     * 用于实现软删除功能，保留数据但标记为已删除
     * 
     * 【设计原因】
     * 1. 数据合规：某些用户数据需要保留以满足审计要求
     * 2. 数据分析：删除的用户数据对于流失分析仍有价值
     * 3. 恢复需求：误删除的数据可以恢复
     * 4. 引用完整性：避免删除用户时影响关联数据
     * 
     * 【使用规则】
     * - false：正常状态，用户可见和可操作
     * - true：已删除状态，查询时默认过滤掉
     */
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    // ===================================================================
    // 枚举定义
    // ===================================================================

    /**
     * 性别枚举
     * 支持标准的性别分类，便于数据分析
     */
    public enum Gender {
        MALE("男性"),
        FEMALE("女性"),
        OTHER("其他"),
        UNKNOWN("未知");

        private final String description;

        Gender(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        /**
         * 从字符串创建性别枚举，支持中英文
         * 
         * @param value 字符串值
         * @return 对应的性别枚举
         */
        public static Gender fromString(String value) {
            if (value == null || value.trim().isEmpty()) {
                return UNKNOWN;
            }

            String normalized = value.trim().toUpperCase();
            
            // 支持英文
            try {
                return Gender.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                // 支持中文映射
                return switch (value.trim()) {
                    case "男", "男性" -> MALE;
                    case "女", "女性" -> FEMALE;
                    case "其他" -> OTHER;
                    default -> UNKNOWN;
                };
            }
        }
    }

    /**
     * 年龄段枚举
     * 用于用户画像分析和精准营销
     * 
     * 【分段策略】
     * - 基于消费行为和数字化程度进行划分
     * - 便于营销策略制定和产品推荐
     */
    public enum AgeGroup {
        TEEN("13-17", "青少年", "数字原住民，娱乐和学习需求为主"),
        YOUNG_ADULT("18-25", "青年", "求学就业阶段，价格敏感，追求性价比"),
        ADULT("26-35", "成年", "事业发展期，消费能力强，注重品质"),
        MIDDLE_AGED("36-50", "中年", "事业成熟期，家庭责任重，注重实用性"),
        SENIOR("51-65", "准老年", "财务稳定，注重健康和品质生活"),
        ELDERLY("65+", "老年", "退休群体，注重健康和便利性"),
        UNKNOWN("未知", "未知", "年龄信息缺失或无效");

        private final String range;
        private final String description;
        private final String characteristics;

        AgeGroup(String range, String description, String characteristics) {
            this.range = range;
            this.description = description;
            this.characteristics = characteristics;
        }

        public String getRange() {
            return range;
        }

        public String getDescription() {
            return description;
        }

        public String getCharacteristics() {
            return characteristics;
        }

        /**
         * 根据年龄计算年龄段
         * 
         * @param age 年龄
         * @return 对应的年龄段枚举
         */
        public static AgeGroup fromAge(int age) {
            if (age < 0 || age > 150) {
                return UNKNOWN;
            }
            
            if (age >= 13 && age <= 17) return TEEN;
            if (age >= 18 && age <= 25) return YOUNG_ADULT;
            if (age >= 26 && age <= 35) return ADULT;
            if (age >= 36 && age <= 50) return MIDDLE_AGED;
            if (age >= 51 && age <= 65) return SENIOR;
            if (age > 65) return ELDERLY;
            
            return UNKNOWN;
        }

        /**
         * 从字符串创建年龄段枚举
         * 
         * @param value 字符串值
         * @return 对应的年龄段枚举
         */
        public static AgeGroup fromString(String value) {
            if (value == null || value.trim().isEmpty()) {
                return UNKNOWN;
            }

            String normalized = value.trim().toUpperCase();
            
            // 支持英文枚举名
            try {
                return AgeGroup.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                // 支持中文描述
                for (AgeGroup group : AgeGroup.values()) {
                    if (group.description.equals(value.trim()) || 
                        group.range.equals(value.trim())) {
                        return group;
                    }
                }
                return UNKNOWN;
            }
        }

        /**
         * 检查是否属于数字化程度较高的年龄段
         * 
         * @return 是否为数字化用户群体
         */
        public boolean isDigitalNative() {
            return this == TEEN || this == YOUNG_ADULT || this == ADULT;
        }

        /**
         * 检查是否属于高消费能力年龄段
         * 
         * @return 是否为高消费能力群体
         */
        public boolean isHighSpendingPower() {
            return this == ADULT || this == MIDDLE_AGED || this == SENIOR;
        }
    }

    // ===================================================================
    // 业务方法
    // ===================================================================

    /**
     * 检查用户是否为新用户
     * 根据注册时间判断（注册不超过30天）
     * 
     * @return 是否为新用户
     */
    public boolean isNewUser() {
        if (registrationDate == null) {
            return false;
        }
        
        Instant thirtyDaysAgo = Instant.now().minusSeconds(30 * 24 * 60 * 60);
        return registrationDate.isAfter(thirtyDaysAgo);
    }

    /**
     * 获取用户注册天数
     * 
     * @return 注册天数
     */
    public long getRegistrationDays() {
        if (registrationDate == null) {
            return 0;
        }
        
        return java.time.Duration.between(registrationDate, Instant.now()).toDays();
    }

    /**
     * 检查是否有完整的基础信息
     * 基础信息包括：用户ID、注册时间、性别、来源渠道
     * 
     * @return 基础信息是否完整
     */
    public boolean hasCompleteBasicInfo() {
        return userId != null && 
               registrationDate != null && 
               gender != null && gender != Gender.UNKNOWN &&
               sourceChannel != null && !sourceChannel.trim().isEmpty();
    }

    /**
     * 检查是否有完整的详细信息
     * 详细信息包括基础信息 + 姓名、邮箱、手机号、城市、年龄段
     * 
     * @return 详细信息是否完整
     */
    public boolean hasCompleteDetailedInfo() {
        return hasCompleteBasicInfo() &&
               realName != null && !realName.trim().isEmpty() &&
               email != null && !email.trim().isEmpty() &&
               phoneNumber != null && !phoneNumber.trim().isEmpty() &&
               city != null && !city.trim().isEmpty() &&
               ageGroup != null && ageGroup != AgeGroup.UNKNOWN;
    }

    /**
     * 检查邮箱格式是否有效
     * 简单的邮箱格式验证
     * 
     * @return 邮箱格式是否有效
     */
    public boolean hasValidEmail() {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        
        // 简单的邮箱格式验证
        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        return email.matches(emailRegex);
    }

    /**
     * 检查手机号格式是否有效（中国手机号）
     * 支持11位中国手机号验证
     * 
     * @return 手机号格式是否有效
     */
    public boolean hasValidPhoneNumber() {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }
        
        // 中国手机号验证：11位数字，以1开头
        String phoneRegex = "^1[3-9]\\d{9}$";
        return phoneNumber.matches(phoneRegex);
    }

    /**
     * 获取用户画像完整度分数
     * 
     * @return 完整度分数（0-100）
     */
    public int getProfileCompletenessScore() {
        int score = 0;
        int totalFields = 9; // 总共9个主要字段

        if (userId != null) score++;
        if (registrationDate != null) score++;
        if (gender != null && gender != Gender.UNKNOWN) score++;
        if (sourceChannel != null && !sourceChannel.trim().isEmpty()) score++;
        if (realName != null && !realName.trim().isEmpty()) score++;
        if (hasValidEmail()) score++;
        if (hasValidPhoneNumber()) score++;
        if (city != null && !city.trim().isEmpty()) score++;
        if (ageGroup != null && ageGroup != AgeGroup.UNKNOWN) score++;

        return (score * 100) / totalFields;
    }

    // ===================================================================
    // 软删除相关方法
    // ===================================================================

    /**
     * 检查用户是否已被软删除
     * 
     * @return 是否已删除
     */
    public boolean isDeleted() {
        return isDeleted != null && isDeleted;
    }

    /**
     * 检查用户是否处于活跃状态（未删除）
     * 
     * @return 是否活跃
     */
    public boolean isActive() {
        return !isDeleted();
    }

    /**
     * 软删除用户
     * 标记为已删除但不实际删除数据
     */
    public void softDelete() {
        this.isDeleted = true;
    }

    /**
     * 恢复已软删除的用户
     * 将删除标记置为false
     */
    public void restore() {
        this.isDeleted = false;
    }

    /**
     * 设置删除状态
     * 
     * @param deleted 是否删除
     */
    public void setDeleted(Boolean deleted) {
        this.isDeleted = deleted != null ? deleted : false;
    }

    /**
     * 获取删除状态
     * 
     * @return 删除状态
     */
    public Boolean getIsDeleted() {
        return isDeleted;
    }

    @Override
    public String toString() {
        return String.format(
            "StaticUserProfile{userId='%s', realName='%s', email='%s', " +
            "phoneNumber='%s', city='%s', registrationDate=%s, gender=%s, " +
            "ageGroup=%s, sourceChannel='%s'}",
            userId, realName, email, phoneNumber, city, 
            registrationDate, gender, ageGroup, sourceChannel
        );
    }
}