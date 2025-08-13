package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.document.UserProfileDocument;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.domain.UserProfileSnapshot;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile;
import com.pulsehub.profileservice.repository.UserProfileDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * MongoDB 用户画像文档服务
 * 
 * 【核心职责】
 * 1. 管理 MongoDB 中的用户画像文档生命周期
 * 2. 聚合来自 PostgreSQL 和 Redis 的用户数据
 * 3. 提供丰富的查询接口和业务分析功能
 * 4. 支持 Schemaless 动态字段扩展
 * 
 * 【架构设计】
 * - 数据源整合：PostgreSQL(静态) + Redis(动态) → MongoDB(完整画像)
 * - 更新策略：增量更新，保持数据一致性
 * - 查询优化：利用 MongoDB 索引和聚合管道
 * - 扩展性：支持动态字段和第三方数据集成
 * 
 * 【业务价值】
 * - CDP 中央画像存储：统一的用户视图
 * - 实时分析支持：高性能查询和聚合
 * - 个性化推荐：丰富的用户特征数据
 * - 营销自动化：精准的用户分群和标签
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileDocumentService {

    private final UserProfileDocumentRepository documentRepository;
    private final ProfileAggregationService profileAggregationService;
    
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String ARCHIVED_STATUS = "ARCHIVED";
    private static final String DELETED_STATUS = "DELETED";

    // ===================================================================
    // 核心 CRUD 操作
    // ===================================================================

    /**
     * 创建或更新用户画像文档
     * 
     * 【更新策略】
     * 1. 从聚合服务获取最新的用户画像快照
     * 2. 转换为 MongoDB 文档格式
     * 3. 保留现有的扩展字段和计算指标
     * 4. 更新时间戳和版本信息
     * 
     * @param userId 用户ID
     * @return 创建或更新的用户画像文档
     */
    public Optional<UserProfileDocument> createOrUpdateDocument(String userId) {
        log.info("🔄 创建或更新用户画像文档: {}", userId);
        
        try {
            // 从聚合服务获取最新的用户画像快照
            Optional<UserProfileSnapshot> snapshotOpt = profileAggregationService.getRealtimeProfile(userId);
            
            if (snapshotOpt.isEmpty()) {
                log.warn("⚠️ 用户 {} 没有可用的画像数据，跳过文档创建", userId);
                return Optional.empty();
            }
            
            UserProfileSnapshot snapshot = snapshotOpt.get();
            
            // 查找现有文档或创建新文档
            UserProfileDocument document = documentRepository
                .findByUserIdAndStatus(userId, ACTIVE_STATUS)
                .orElse(UserProfileDocument.builder()
                    .userId(userId)
                    .status(ACTIVE_STATUS)
                    .createdAt(Instant.now())
                    .dataVersion("1.0")
                    .build());
            
            // 更新基础信息
            updateDocumentFromSnapshot(document, snapshot);
            
            // 保存文档
            UserProfileDocument savedDocument = documentRepository.save(document);
            
            log.info("✅ 用户画像文档更新成功: {} (版本: {})", userId, savedDocument.getDataVersion());
            return Optional.of(savedDocument);
            
        } catch (Exception e) {
            log.error("❌ 创建或更新用户画像文档失败: {}", userId, e);
            return Optional.empty();
        }
    }

    /**
     * 获取活跃用户画像文档
     * 
     * @param userId 用户ID
     * @return 用户画像文档
     */
    public Optional<UserProfileDocument> getActiveDocument(String userId) {
        log.debug("📖 获取活跃用户画像文档: {}", userId);
        return documentRepository.findByUserIdAndStatus(userId, ACTIVE_STATUS);
    }

    /**
     * 删除用户画像文档（逻辑删除）
     * 
     * @param userId 用户ID
     * @return 是否删除成功
     */
    public boolean deleteDocument(String userId) {
        log.info("🗑️ 删除用户画像文档: {}", userId);
        
        try {
            Optional<UserProfileDocument> documentOpt = getActiveDocument(userId);
            if (documentOpt.isPresent()) {
                UserProfileDocument document = documentOpt.get();
                document.markAsDeleted();
                documentRepository.save(document);
                
                log.info("✅ 用户画像文档已标记为删除: {}", userId);
                return true;
            } else {
                log.warn("⚠️ 用户画像文档不存在: {}", userId);
                return false;
            }
        } catch (Exception e) {
            log.error("❌ 删除用户画像文档失败: {}", userId, e);
            return false;
        }
    }

    // ===================================================================
    // 批量操作
    // ===================================================================

    /**
     * 批量创建或更新用户画像文档
     * 
     * @param userIds 用户ID列表
     * @return 成功处理的用户ID列表
     */
    public List<String> batchCreateOrUpdate(List<String> userIds) {
        log.info("📋 批量创建或更新用户画像文档: {} 个用户", userIds.size());
        
        List<CompletableFuture<String>> futures = userIds.stream()
            .map(userId -> CompletableFuture
                    // 遍历所有的 userId,  调用 createOrUpdateDocument(userId) 创建最新的 doc, 添加或更新 mongodb
                .supplyAsync(() -> createOrUpdateDocument(userId))
                .thenApply(result -> result.map(UserProfileDocument::getUserId).orElse(null)))
            .collect(Collectors.toList());
        
        List<String> successfulUserIds = futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        log.info("✅ 批量处理完成: {}/{} 成功", successfulUserIds.size(), userIds.size());
        return successfulUserIds;
    }

    // ===================================================================
    // 业务查询方法
    // ===================================================================

    /**
     * 根据城市查询用户
     * 
     * @param city 城市名称
     * @return 用户画像文档页面
     */
    public List<UserProfileDocument> findUsersByCity(String city) {
        log.info("🏙️ 根据城市查询用户: {}", city);
        return documentRepository.findByCityAndStatus(city, ACTIVE_STATUS);
    }

    /**
     * 根据设备分类查询用户
     * 
     * @param deviceClass 设备分类
     * @return 用户画像文档列表
     */
    public List<UserProfileDocument> findUsersByDeviceClass(String deviceClass) {
        log.info("📱 根据设备分类查询用户: {}", deviceClass);
        return documentRepository.findByDeviceClassificationAndStatus(deviceClass, ACTIVE_STATUS);
    }

    /**
     * 查询高价值活跃用户
     * 
     * @param minValueScore 最小价值分数
     * @param since 活跃时间起点
     * @return 高价值活跃用户列表
     */
    public List<UserProfileDocument> findHighValueActiveUsers(Integer minValueScore, Instant since) {
        log.info("💎 查询高价值活跃用户: 分数>={}, 活跃时间>={}", minValueScore, since);
        
        List<String> activityLevels = Arrays.asList("VERY_ACTIVE", "ACTIVE");
        return documentRepository.findHighValueActiveUsers(minValueScore, activityLevels, since, ACTIVE_STATUS);
    }

    /**
     * 根据兴趣查询用户
     * 
     * @param interest 兴趣关键词
     * @return 用户画像文档列表
     */
    public List<UserProfileDocument> findUsersByInterest(String interest) {
        log.info("💡 根据兴趣查询用户: {}", interest);
        return documentRepository.findByInterestAndStatus(interest, ACTIVE_STATUS);
    }

    /**
     * 根据职业查询用户
     * 
     * @param industry 行业
     * @return 用户画像文档列表
     */
    public List<UserProfileDocument> findUsersByIndustry(String industry) {
        log.info("💼 根据行业查询用户: {}", industry);
        return documentRepository.findByIndustryAndStatus(industry, ACTIVE_STATUS);
    }

    // ===================================================================
    // 标签管理
    // ===================================================================

    /**
     * 为用户添加标签
     * 
     * @param userId 用户ID
     * @param tag 标签
     * @return 是否添加成功
     */
    public boolean addTagToUser(String userId, String tag) {
        log.info("🏷️ 为用户添加标签: {} -> {}", userId, tag);
        
        try {
            Optional<UserProfileDocument> documentOpt = getActiveDocument(userId);
            if (documentOpt.isPresent()) {
                UserProfileDocument document = documentOpt.get();
                document.addTag(tag);
                documentRepository.save(document);
                
                log.info("✅ 标签添加成功: {} -> {}", userId, tag);
                return true;
            }
        } catch (Exception e) {
            log.error("❌ 添加标签失败: {} -> {}", userId, tag, e);
        }
        return false;
    }

    /**
     * 批量为用户添加标签
     * 
     * @param userIds 用户ID列表
     * @param tag 标签
     * @return 成功添加标签的用户数量
     */
    public long batchAddTag(List<String> userIds, String tag) {
        log.info("🏷️ 批量添加标签: {} 个用户 -> {}", userIds.size(), tag);
        
        return userIds.stream()
            .mapToLong(userId -> addTagToUser(userId, tag) ? 1 : 0)
            .sum();
    }

    /**
     * 根据标签查询用户
     * 
     * @param tag 标签
     * @return 用户画像文档列表
     */
    public List<UserProfileDocument> findUsersByTag(String tag) {
        log.info("🔍 根据标签查询用户: {}", tag);
        return documentRepository.findByTagsContainingAndStatus(tag, ACTIVE_STATUS);
    }

    // ===================================================================
    // 动态字段管理
    // ===================================================================

    /**
     * 设置用户的社交媒体数据
     * 
     * @param userId 用户ID
     * @param platform 平台名称
     * @param data 社交媒体数据
     * @return 是否设置成功
     */
    public boolean setSocialMediaData(String userId, String platform, Map<String, Object> data) {
        log.info("📱 设置用户社交媒体数据: {} -> {}", userId, platform);
        
        try {
            Optional<UserProfileDocument> documentOpt = getActiveDocument(userId);
            if (documentOpt.isPresent()) {
                UserProfileDocument document = documentOpt.get();
                document.setSocialMediaData(platform, data);
                documentRepository.save(document);
                
                log.info("✅ 社交媒体数据设置成功: {} -> {}", userId, platform);
                return true;
            }
        } catch (Exception e) {
            log.error("❌ 设置社交媒体数据失败: {} -> {}", userId, platform, e);
        }
        return false;
    }

    /**
     * 设置用户的计算指标
     * 
     * @param userId 用户ID
     * @param metricName 指标名称
     * @param value 指标值
     * @return 是否设置成功
     */
    public boolean setComputedMetric(String userId, String metricName, Object value) {
        log.info("📊 设置用户计算指标: {} -> {} = {}", userId, metricName, value);
        
        try {
            Optional<UserProfileDocument> documentOpt = getActiveDocument(userId);
            if (documentOpt.isPresent()) {
                UserProfileDocument document = documentOpt.get();
                document.setComputedMetric(metricName, value);
                documentRepository.save(document);
                
                log.info("✅ 计算指标设置成功: {} -> {} = {}", userId, metricName, value);
                return true;
            }
        } catch (Exception e) {
            log.error("❌ 设置计算指标失败: {} -> {} = {}", userId, metricName, value, e);
        }
        return false;
    }

    /**
     * 设置用户的扩展属性
     * 
     * @param userId 用户ID
     * @param key 属性键
     * @param value 属性值
     * @return 是否设置成功
     */
    public boolean setExtendedProperty(String userId, String key, Object value) {
        log.info("🔧 设置用户扩展属性: {} -> {} = {}", userId, key, value);
        
        try {
            Optional<UserProfileDocument> documentOpt = getActiveDocument(userId);
            if (documentOpt.isPresent()) {
                UserProfileDocument document = documentOpt.get();
                document.setExtendedProperty(key, value);
                documentRepository.save(document);
                
                log.info("✅ 扩展属性设置成功: {} -> {} = {}", userId, key, value);
                return true;
            }
        } catch (Exception e) {
            log.error("❌ 设置扩展属性失败: {} -> {} = {}", userId, key, value, e);
        }
        return false;
    }

    // ===================================================================
    // 统计分析方法
    // ===================================================================

    /**
     * 获取活跃用户统计
     * 
     * @return 活跃用户数量
     */
    public long getActiveUserCount() {
        return documentRepository.countByStatus(ACTIVE_STATUS);
    }

    /**
     * 获取指定时间后活跃的用户数量
     * 
     * @param since 时间起点
     * @return 活跃用户数量
     */
    public long getActiveUserCountSince(Instant since) {
        return documentRepository.countByLastActiveAtAfterAndStatus(since, ACTIVE_STATUS);
    }

    /**
     * 获取指定城市的用户数量统计
     * 
     * @param city 城市
     * @return 用户数量
     */
    public long getUserCountByCity(String city) {
        return documentRepository.countByCityAndStatus(city, ACTIVE_STATUS);
    }

    /**
     * 获取服务状态信息
     * 
     * @return 服务状态
     */
    public ServiceStatus getServiceStatus() {
        log.info("📊 获取 MongoDB 文档服务状态");
        
        ServiceStatus status = new ServiceStatus();
        
        try {
            long totalCount = documentRepository.count();
            long activeCount = getActiveUserCount();
            
            status.setTotalDocumentCount(totalCount);
            status.setActiveDocumentCount(activeCount);
            status.setMongoDbHealthy(true);
            
            // 检查最近是否有文档更新
            List<UserProfileDocument> recentDocuments = documentRepository
                .findByLastActiveAtAfterAndStatusOrderByLastActiveAtDesc(
                    Instant.now().minusSeconds(3600), ACTIVE_STATUS);
            
            status.setRecentActivityCount(recentDocuments.size());
            status.setOverallHealthy(true);
            
        } catch (Exception e) {
            status.setMongoDbHealthy(false);
            status.setOverallHealthy(false);
            status.addError("MongoDB 连接异常: " + e.getMessage());
            log.error("❌ MongoDB 服务状态检查失败", e);
        }
        
        return status;
    }

    // ===================================================================
    // 辅助方法
    // ===================================================================

    /**
     * 从用户画像快照更新文档
     */
    private void updateDocumentFromSnapshot(UserProfileDocument document, UserProfileSnapshot snapshot) {
        // 更新基础时间戳
        document.updateTimestamp();
        
        // 更新注册时间和最后活跃时间
        if (snapshot.getRegistrationDate() != null) {
            document.setRegistrationDate(snapshot.getRegistrationDate());
        }
        if (snapshot.getLastActiveAt() != null) {
            document.setLastActiveAt(snapshot.getLastActiveAt());
        }
        
        // 更新静态画像数据
        Map<String, Object> staticProfile = new HashMap<>();
        if (snapshot.getGender() != null) {
            staticProfile.put("gender", snapshot.getGender().toString());
        }
        if (snapshot.getRealName() != null) {
            staticProfile.put("real_name", snapshot.getRealName());
        }
        if (snapshot.getEmail() != null) {
            staticProfile.put("email", snapshot.getEmail());
        }
        if (snapshot.getPhoneNumber() != null) {
            staticProfile.put("phone_number", snapshot.getPhoneNumber());
        }
        if (snapshot.getCity() != null) {
            staticProfile.put("city", snapshot.getCity());
        }
        if (snapshot.getAgeGroup() != null) {
            staticProfile.put("age_group", snapshot.getAgeGroup().toString());
        }
        if (snapshot.getSourceChannel() != null) {
            staticProfile.put("source_channel", snapshot.getSourceChannel());
        }
        
        if (!staticProfile.isEmpty()) {
            document.setStaticProfile(staticProfile);
        }
        
        // 更新动态画像数据
        Map<String, Object> dynamicProfile = new HashMap<>();
        if (snapshot.getPageViewCount() != null) {
            dynamicProfile.put("page_view_count", snapshot.getPageViewCount());
        }
        if (snapshot.getDeviceClassification() != null) {
            dynamicProfile.put("device_classification", snapshot.getDeviceClassification().toString());
        }
        if (snapshot.getRecentDeviceTypes() != null && !snapshot.getRecentDeviceTypes().isEmpty()) {
            dynamicProfile.put("recent_device_types", snapshot.getRecentDeviceTypes().stream()
                .map(Object::toString)
                .collect(Collectors.toList()));
        }
        if (snapshot.getDynamicVersion() != null) {
            dynamicProfile.put("dynamic_version", snapshot.getDynamicVersion());
        }
        if (snapshot.getDynamicUpdatedAt() != null) {
            dynamicProfile.put("dynamic_updated_at", snapshot.getDynamicUpdatedAt());
        }
        
        if (!dynamicProfile.isEmpty()) {
            document.setDynamicProfile(dynamicProfile);
        }
        
        // 更新计算指标
        Map<String, Object> computedMetrics = new HashMap<>();
        computedMetrics.put("activity_level", snapshot.isActiveUser() ? "ACTIVE" : "INACTIVE");
        computedMetrics.put("value_score", snapshot.getValueScore());
        computedMetrics.put("profile_completeness", 0);
        
        if (snapshot.isHighValueUser()) {
            computedMetrics.put("lifecycle_stage", "HIGH_VALUE");
        }
        
        document.setComputedMetrics(computedMetrics);
        
        // 添加自动标签
        if (snapshot.isActiveUser()) {
            document.addTag("active_user");
        }
        if (snapshot.isHighValueUser()) {
            document.addTag("high_value_user");
        }
        if (snapshot.getDeviceClassification() != null) {
            document.addTag("device_" + snapshot.getDeviceClassification().toString().toLowerCase());
        }
    }

    /**
     * 服务状态信息
     */
    @lombok.Data
    public static class ServiceStatus {
        private boolean overallHealthy;
        private boolean mongoDbHealthy;
        private long totalDocumentCount;
        private long activeDocumentCount;
        private long recentActivityCount;
        private final List<String> errors = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
    }
}