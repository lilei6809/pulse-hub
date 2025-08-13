package com.pulsehub.profileservice.repository;

import com.pulsehub.profileservice.document.UserProfileDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB 用户画像文档仓储接口
 * 
 * 【设计理念】
 * 利用 MongoDB 的灵活查询能力，支持：
 * 1. 传统关系型查询（基于索引字段）
 * 2. 文档内嵌字段查询（dot notation）
 * 3. 复杂聚合查询（aggregation pipeline）
 * 4. 全文搜索和模糊匹配
 * 
 * 【查询策略】
 * - 高频查询：基于索引字段（userId, lastActiveAt, registrationDate等）
 * - 复杂查询：使用 @Query 注解编写 MongoDB 原生查询
 * - 灵活查询：支持动态字段查询，适应 schemaless 特性
 */
@Repository
public interface UserProfileDocumentRepository extends MongoRepository<UserProfileDocument, String> {

    // ===================================================================
    // 基础查询方法
    // ===================================================================

    /**
     * 根据用户ID查找活跃文档
     */
    Optional<UserProfileDocument> findByUserIdAndStatus(String userId, String status);

    /**
     * 查找所有活跃用户
     */
    List<UserProfileDocument> findByStatusOrderByLastActiveAtDesc(String status);

    /**
     * 根据注册时间范围查询
     */
    List<UserProfileDocument> findByRegistrationDateBetweenAndStatus(
        Instant startDate, Instant endDate, String status);

    /**
     * 根据最后活跃时间查询活跃用户
     */
    List<UserProfileDocument> findByLastActiveAtAfterAndStatusOrderByLastActiveAtDesc(
        Instant since, String status);

    // ===================================================================
    // 动态字段查询（利用 MongoDB 的 dot notation）
    // ===================================================================

    /**
     * 根据静态画像中的城市查询
     * 查询条件：static_profile.city = "北京"
     */
    @Query("{'static_profile.city': ?0, 'status': ?1}")
    List<UserProfileDocument> findByCityAndStatus(String city, String status);

    /**
     * 根据静态画像中的性别查询
     * 查询条件：static_profile.gender = "MALE"
     */
    @Query("{'static_profile.gender': ?0, 'status': ?1}")
    List<UserProfileDocument> findByGenderAndStatus(String gender, String status);

    /**
     * 根据动态画像中的设备分类查询
     * 查询条件：dynamic_profile.device_classification = "MOBILE"
     */
    @Query("{'dynamic_profile.device_classification': ?0, 'status': ?1}")
    List<UserProfileDocument> findByDeviceClassificationAndStatus(String deviceClass, String status);

    /**
     * 根据计算指标中的活跃度查询
     * 查询条件：computed_metrics.activity_level = "VERY_ACTIVE"
     */
    @Query("{'computed_metrics.activity_level': ?0, 'status': ?1}")
    List<UserProfileDocument> findByActivityLevelAndStatus(String activityLevel, String status);

    /**
     * 根据计算指标中的价值评分范围查询
     * 查询条件：computed_metrics.value_score >= minScore AND computed_metrics.value_score <= maxScore
     */
    @Query("{'computed_metrics.value_score': {$gte: ?0, $lte: ?1}, 'status': ?2}")
    List<UserProfileDocument> findByValueScoreRangeAndStatus(
        Integer minScore, Integer maxScore, String status);

    // ===================================================================
    // 社交媒体数据查询
    // ===================================================================

    /**
     * 查询拥有指定社交媒体平台数据的用户
     * 查询条件：social_media.threads 字段存在
     */
    @Query("{'social_media.?0': {$exists: true}, 'status': ?1}")
    List<UserProfileDocument> findBySocialMediaPlatformExistsAndStatus(String platform, String status);

    /**
     * 根据 Instagram 粉丝数查询
     * 查询条件：social_media.instagram.followers_count >= minFollowers
     */
    @Query("{'social_media.instagram.followers_count': {$gte: ?0}, 'status': ?1}")
    List<UserProfileDocument> findByInstagramFollowersAndStatus(Integer minFollowers, String status);

    /**
     * 根据 LinkedIn 行业查询
     * 查询条件：social_media.linkedin.industry = "Technology"
     */
    @Query("{'social_media.linkedin.industry': ?0, 'status': ?1}")
    List<UserProfileDocument> findByLinkedInIndustryAndStatus(String industry, String status);

    // ===================================================================
    // 兴趣爱好查询
    // ===================================================================

    /**
     * 根据兴趣查询（数组包含查询）
     * 查询条件：interests_preferences.interests 包含指定兴趣
     */
    @Query("{'interests_preferences.interests': {$in: [?0]}, 'status': ?1}")
    List<UserProfileDocument> findByInterestAndStatus(String interest, String status);

    /**
     * 根据购物类别查询
     * 查询条件：interests_preferences.shopping_categories 包含指定类别
     */
    @Query("{'interests_preferences.shopping_categories': {$in: [?0]}, 'status': ?1}")
    List<UserProfileDocument> findByShoppingCategoryAndStatus(String category, String status);

    // ===================================================================
    // 职业信息查询
    // ===================================================================

    /**
     * 根据当前职位查询
     * 查询条件：professional_info.current_position 包含关键词
     */
    @Query("{'professional_info.current_position': {$regex: ?0, $options: 'i'}, 'status': ?1}")
    List<UserProfileDocument> findByCurrentPositionContainingAndStatus(String positionKeyword, String status);

    /**
     * 根据行业查询
     * 查询条件：professional_info.industry = "Software Development"
     */
    @Query("{'professional_info.industry': ?0, 'status': ?1}")
    List<UserProfileDocument> findByIndustryAndStatus(String industry, String status);

    /**
     * 根据公司查询
     * 查询条件：professional_info.company 包含关键词
     */
    @Query("{'professional_info.company': {$regex: ?0, $options: 'i'}, 'status': ?1}")
    List<UserProfileDocument> findByCompanyContainingAndStatus(String companyKeyword, String status);

    // ===================================================================
    // 行为数据查询
    // ===================================================================

    /**
     * 根据平均订单价值查询
     * 查询条件：behavioral_data.purchase_behavior.avg_order_value >= minValue
     */
    @Query("{'behavioral_data.purchase_behavior.avg_order_value': {$gte: ?0}, 'status': ?1}")
    List<UserProfileDocument> findByAvgOrderValueAndStatus(Double minValue, String status);

    /**
     * 根据购买频率查询
     * 查询条件：behavioral_data.purchase_behavior.purchase_frequency = "monthly"
     */
    @Query("{'behavioral_data.purchase_behavior.purchase_frequency': ?0, 'status': ?1}")
    List<UserProfileDocument> findByPurchaseFrequencyAndStatus(String frequency, String status);

    // ===================================================================
    // 标签查询
    // ===================================================================

    /**
     * 根据标签查询（数组包含查询）
     */
    List<UserProfileDocument> findByTagsContainingAndStatus(String tag, String status);

    /**
     * 根据多个标签查询（包含所有指定标签）
     */
    @Query("{'tags': {$all: ?0}, 'status': ?1}")
    List<UserProfileDocument> findByAllTagsAndStatus(List<String> tags, String status);

    // ===================================================================
    // 复合查询
    // ===================================================================

    /**
     * 查询高价值活跃用户
     * 查询条件：
     * - computed_metrics.value_score >= 80
     * - computed_metrics.activity_level in ["VERY_ACTIVE", "ACTIVE"]
     * - last_active_at >= 指定时间
     */
    @Query("{ " +
           "'computed_metrics.value_score': {$gte: ?0}, " +
           "'computed_metrics.activity_level': {$in: ?1}, " +
           "'last_active_at': {$gte: ?2}, " +
           "'status': ?3 " +
           "}")
    List<UserProfileDocument> findHighValueActiveUsers(
        Integer minValueScore, 
        List<String> activityLevels, 
        Instant since, 
        String status);

    /**
     * 查询指定地区的技术从业者
     * 查询条件：
     * - static_profile.city in 城市列表
     * - professional_info.industry 包含 "Technology" 或 "Software"
     */
    @Query("{ " +
           "'static_profile.city': {$in: ?0}, " +
           "'professional_info.industry': {$regex: ?1, $options: 'i'}, " +
           "'status': ?2 " +
           "}")
    List<UserProfileDocument> findTechWorkersInCities(
        List<String> cities, 
        String industryPattern, 
        String status);

    // ===================================================================
    // 统计查询
    // ===================================================================

    /**
     * 统计指定状态的用户数量
     */
    long countByStatus(String status);

    /**
     * 统计指定时间后活跃的用户数量
     */
    long countByLastActiveAtAfterAndStatus(Instant since, String status);

    /**
     * 统计指定城市的用户数量
     */
    @Query(value = "{'static_profile.city': ?0, 'status': ?1}", count = true)
    long countByCityAndStatus(String city, String status);
}