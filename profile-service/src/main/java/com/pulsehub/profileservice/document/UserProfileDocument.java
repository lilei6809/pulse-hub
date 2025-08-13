package com.pulsehub.profileservice.document;

import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Set;

/**
 * MongoDB 用户画像文档模型 - Schemaless 设计
 * 
 * 【设计理念：向后兼容的 Schemaless 架构】
 * 该文档采用 MongoDB 的 schemaless 特性，支持动态字段扩展：
 * 
 * 1. 核心字段：定义业务必需的基础字段（userId, timestamps等）
 * 2. 标准字段：定义当前已知的用户属性字段，但不强制要求
 * 3. 动态扩展：通过 profile_data 支持任意新字段的添加
 * 4. 版本兼容：新版本可以无缝处理旧版本的文档
 * 
 * 【扩展场景举例】
 * - 社交媒体集成：threads_count, instagram_followers, linkedin_connections
 * - 兴趣爱好：interests[], work_experience[], education_background[]
 * - 行为偏好：preferred_content_types[], shopping_categories[]
 * - AI分析结果：sentiment_analysis, personality_traits, risk_profile
 * - 第三方数据：credit_score, social_influence_score, purchase_power
 * 
 * 【存储策略】
 * - 主要用途：作为 CDP 的中央用户画像存储
 * - 数据来源：聚合来自多个数据源（PostgreSQL、Redis、第三方API）
 * - 更新策略：增量更新，保持历史版本追踪
 * - 查询优化：核心字段建立索引，动态字段支持灵活查询
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_profiles")
public class UserProfileDocument {

    /**
     * MongoDB 文档ID，使用用户ID作为主键
     */
    @Id
    private String userId;

    /**
     * 文档创建时间
     */
    @Field("created_at")
    @Indexed
    private Instant createdAt;

    /**
     * 文档最后更新时间
     */
    @Field("updated_at")
    @Indexed
    private Instant updatedAt;

    /**
     * 数据版本标识
     */
    @Field("data_version")
    private String dataVersion;

    // ===================================================================
    // 核心业务字段（保留在 Java 类中便于业务逻辑处理）
    // ===================================================================

    /**
     * 用户注册时间（核心业务字段）
     */
    @Field("registration_date")
    @Indexed
    private Instant registrationDate;

    /**
     * 最后活跃时间（核心业务字段）
     */
    @Field("last_active_at")
    @Indexed
    private Instant lastActiveAt;

    // ===================================================================
    // Schemaless 动态数据存储
    // ===================================================================

    /**
     * 静态画像数据（来自 PostgreSQL）
     * 
     * 示例结构：
     * {
     *   "gender": "MALE",
     *   "source_channel": "mobile_app",
     *   "real_name": "张三",
     *   "email": "zhangsan@example.com",
     *   "phone_number": "+86138****1234",
     *   "city": "北京",
     *   "age_group": "YOUNG_ADULT"
     * }
     */
    @Field("static_profile")
    private java.util.Map<String, Object> staticProfile;

    /**
     * 动态画像数据（来自 Redis）
     * 
     * 示例结构：
     * {
     *   "page_view_count": 1250,
     *   "device_classification": "MOBILE",
     *   "recent_device_types": ["MOBILE", "DESKTOP"],
     *   "dynamic_version": 15,
     *   "dynamic_updated_at": "2025-01-15T10:30:00Z"
     * }
     */
    @Field("dynamic_profile")
    private java.util.Map<String, Object> dynamicProfile;

    /**
     * 计算字段（AI/ML 分析结果等）
     * 
     * 示例结构：
     * {
     *   "activity_level": "VERY_ACTIVE",
     *   "lifecycle_stage": "MATURE_USER", 
     *   "value_score": 85,
     *   "profile_completeness": 78,
     *   "personality_traits": {
     *     "openness": 0.7,
     *     "conscientiousness": 0.8
     *   }
     * }
     */
    @Field("computed_metrics")
    @Indexed(sparse = true)  // 稀疏索引，只对存在该字段的文档建立索引
    private java.util.Map<String, Object> computedMetrics;

    /**
     * 社交媒体数据（第三方集成）
     * 
     * 示例结构：
     * {
     *   "threads": {
     *     "username": "@username",
     *     "followers_count": 1500,
     *     "posts_count": 234,
     *     "engagement_rate": 0.05
     *   },
     *   "instagram": {
     *     "followers_count": 3200,
     *     "verified": false
     *   },
     *   "linkedin": {
     *     "connections": 890,
     *     "industry": "Technology"
     *   }
     * }
     */
    @Field("social_media")
    private java.util.Map<String, Object> socialMedia;

    /**
     * 兴趣爱好与偏好
     * 
     * 示例结构：
     * {
     *   "interests": ["technology", "travel", "photography"],
     *   "preferred_content_types": ["video", "article"],
     *   "shopping_categories": ["electronics", "books", "clothing"],
     *   "favorite_brands": ["Apple", "Nike", "Tesla"]
     * }
     */
    @Field("interests_preferences")
    private java.util.Map<String, Object> interestsPreferences;

    /**
     * 职业与教育信息
     * 
     * 示例结构：
     * {
     *   "current_position": "Senior Software Engineer",
     *   "company": "Tech Corp",
     *   "industry": "Software Development",
     *   "work_experience": [
     *     {
     *       "company": "Previous Corp",
     *       "position": "Software Engineer",
     *       "duration_months": 24
     *     }
     *   ],
     *   "education": [
     *     {
     *       "degree": "Bachelor of Computer Science",
     *       "school": "University of Technology",
     *       "graduation_year": 2018
     *     }
     *   ]
     * }
     */
    @Field("professional_info")
    private java.util.Map<String, Object> professionalInfo;

    /**
     * 行为分析数据
     * 
     * 示例结构：
     * {
     *   "browsing_patterns": {
     *     "peak_hours": [9, 12, 20],
     *     "session_duration_avg": 450,
     *     "bounce_rate": 0.25
     *   },
     *   "purchase_behavior": {
     *     "avg_order_value": 156.78,
     *     "purchase_frequency": "monthly",
     *     "preferred_payment": "credit_card"
     *   },
     *   "content_consumption": {
     *     "reading_speed": "fast",
     *     "preferred_topics": ["tech", "business"]
     *   }
     * }
     */
    @Field("behavioral_data")
    private java.util.Map<String, Object> behavioralData;

    // ===================================================================
    // MongoDB 特有字段
    // ===================================================================

    /**
     * 文档状态（ACTIVE, ARCHIVED, DELETED）
     */
    @Field("status")
    @Indexed
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 标签集合（用于灵活的用户分类）
     */
    @Field("tags")
    private Set<String> tags;

    /**
     * 扩展属性（用于存储额外的键值对数据）
     */
    @Field("extended_properties")
    private java.util.Map<String, Object> extendedProperties;

    // ===================================================================
    // 辅助方法 - Schemaless 数据操作
    // ===================================================================

    /**
     * 更新文档时间戳
     */
    public void updateTimestamp() {
        this.updatedAt = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = this.updatedAt;
        }
    }

    /**
     * 添加标签
     */
    public void addTag(String tag) {
        if (tag != null && !tag.trim().isEmpty()) {
            if (this.tags == null) {
                this.tags = new java.util.HashSet<>();
            }
            this.tags.add(tag.trim());
        }
    }

    /**
     * 移除标签
     */
    public void removeTag(String tag) {
        if (this.tags != null && tag != null) {
            this.tags.remove(tag.trim());
        }
    }

    /**
     * 设置扩展属性
     */
    public void setExtendedProperty(String key, Object value) {
        if (key != null && !key.trim().isEmpty()) {
            if (this.extendedProperties == null) {
                this.extendedProperties = new java.util.HashMap<>();
            }
            this.extendedProperties.put(key.trim(), value);
        }
    }

    /**
     * 获取扩展属性
     */
    public Object getExtendedProperty(String key) {
        if (this.extendedProperties != null && key != null) {
            return this.extendedProperties.get(key.trim());
        }
        return null;
    }

    // ===================================================================
    // 动态数据操作方法
    // ===================================================================

    /**
     * 设置静态画像字段
     */
    public void setStaticField(String fieldName, Object value) {
        if (fieldName != null && !fieldName.trim().isEmpty()) {
            if (this.staticProfile == null) {
                this.staticProfile = new java.util.HashMap<>();
            }
            this.staticProfile.put(fieldName, value);
            updateTimestamp();
        }
    }

    /**
     * 获取静态画像字段
     */
    @SuppressWarnings("unchecked")
    public <T> T getStaticField(String fieldName, Class<T> clazz) {
        if (this.staticProfile != null && fieldName != null) {
            Object value = this.staticProfile.get(fieldName);
            if (value != null && clazz.isInstance(value)) {
                return (T) value;
            }
        }
        return null;
    }

    /**
     * 设置动态画像字段
     */
    public void setDynamicField(String fieldName, Object value) {
        if (fieldName != null && !fieldName.trim().isEmpty()) {
            if (this.dynamicProfile == null) {
                this.dynamicProfile = new java.util.HashMap<>();
            }
            this.dynamicProfile.put(fieldName, value);
            updateTimestamp();
        }
    }

    /**
     * 获取动态画像字段
     */
    @SuppressWarnings("unchecked")
    public <T> T getDynamicField(String fieldName, Class<T> clazz) {
        if (this.dynamicProfile != null && fieldName != null) {
            Object value = this.dynamicProfile.get(fieldName);
            if (value != null && clazz.isInstance(value)) {
                return (T) value;
            }
        }
        return null;
    }

    /**
     * 设置社交媒体数据
     */
    public void setSocialMediaData(String platform, java.util.Map<String, Object> data) {
        if (platform != null && !platform.trim().isEmpty() && data != null) {
            if (this.socialMedia == null) {
                this.socialMedia = new java.util.HashMap<>();
            }
            this.socialMedia.put(platform, data);
            updateTimestamp();
        }
    }

    /**
     * 获取社交媒体数据
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getSocialMediaData(String platform) {
        if (this.socialMedia != null && platform != null) {
            Object data = this.socialMedia.get(platform);
            if (data instanceof java.util.Map) {
                return (java.util.Map<String, Object>) data;
            }
        }
        return null;
    }

    /**
     * 设置计算指标
     */
    public void setComputedMetric(String metricName, Object value) {
        if (metricName != null && !metricName.trim().isEmpty()) {
            if (this.computedMetrics == null) {
                this.computedMetrics = new java.util.HashMap<>();
            }
            this.computedMetrics.put(metricName, value);
            updateTimestamp();
        }
    }

    /**
     * 获取计算指标
     */
    @SuppressWarnings("unchecked")
    public <T> T getComputedMetric(String metricName, Class<T> clazz) {
        if (this.computedMetrics != null && metricName != null) {
            Object value = this.computedMetrics.get(metricName);
            if (value != null && clazz.isInstance(value)) {
                return (T) value;
            }
        }
        return null;
    }

    /**
     * 批量更新动态数据（用于从 UserProfileSnapshot 转换）
     */
    public void updateFromSnapshot(java.util.Map<String, Object> snapshotData) {
        if (snapshotData != null && !snapshotData.isEmpty()) {
            // 可以根据字段名称智能分配到不同的数据区域
            snapshotData.forEach((key, value) -> {
                if (isStaticProfileField(key)) {
                    setStaticField(key, value);
                } else if (isDynamicProfileField(key)) {
                    setDynamicField(key, value);
                } else if (isComputedMetricField(key)) {
                    setComputedMetric(key, value);
                }
            });
        }
    }

    /**
     * 判断是否为静态画像字段
     */
    private boolean isStaticProfileField(String fieldName) {
        return fieldName != null && (
            fieldName.equals("gender") || fieldName.equals("real_name") ||
            fieldName.equals("email") || fieldName.equals("phone_number") ||
            fieldName.equals("city") || fieldName.equals("age_group") ||
            fieldName.equals("source_channel")
        );
    }

    /**
     * 判断是否为动态画像字段
     */
    private boolean isDynamicProfileField(String fieldName) {
        return fieldName != null && (
            fieldName.equals("page_view_count") || fieldName.equals("device_classification") ||
            fieldName.equals("recent_device_types") || fieldName.equals("dynamic_version") ||
            fieldName.equals("dynamic_updated_at")
        );
    }

    /**
     * 判断是否为计算指标字段
     */
    private boolean isComputedMetricField(String fieldName) {
        return fieldName != null && (
            fieldName.equals("activity_level") || fieldName.equals("lifecycle_stage") ||
            fieldName.equals("value_score") || fieldName.equals("profile_completeness")
        );
    }

    /**
     * 检查文档是否有效
     */
    public boolean isValid() {
        return userId != null && !userId.trim().isEmpty() &&
               status != null && !status.trim().isEmpty();
    }

    /**
     * 检查是否为活跃文档
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /**
     * 标记为已删除
     */
    public void markAsDeleted() {
        this.status = "DELETED";
        updateTimestamp();
    }

    /**
     * 标记为已归档
     */
    public void markAsArchived() {
        this.status = "ARCHIVED";
        updateTimestamp();
    }
}