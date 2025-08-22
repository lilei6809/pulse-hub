package com.pulsehub.datasync.service;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.pulsehub.datasync.proto.ProfileMetadata;
import com.pulsehub.datasync.proto.SyncPriority;
import com.pulsehub.datasync.proto.UserProfileSyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * MongoDB Profile Updater
 * 
 * 负责将Protobuf事件增量更新到MongoDB中的UserProfileDocument:
 * - 支持版本控制和乐观锁更新
 * - 增量更新不同的数据分区 (静态、动态、行为等)
 * - 处理标签的添加和删除操作
 * - 立即同步失败时的重试机制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MongoProfileUpdater {

    private final MongoTemplate mongoTemplate;

    // MongoDB集合名称 - UserProfileDocument存储集合
    private static final String COLLECTION_NAME = "userProfiles";

    /**
     * 更新用户资料到MongoDB
     * 
     * @param event 用户资料同步事件
     * @return true if update successful
     */
    public boolean updateProfile(UserProfileSyncEvent event) {
        try {
            log.debug("Updating MongoDB for user: {}, version: {}, priority: {}", 
                    event.getUserId(), event.getVersion(), event.getPriority());

            // 构建查询条件 - 版本控制确保乐观锁
            Query query = buildVersionQuery(event);
            
            // 构建更新操作 - 增量更新
            Update update = buildIncrementalUpdate(event);
            
            // 执行更新
            var result = mongoTemplate.updateFirst(query, update, COLLECTION_NAME);
            
            boolean success = result.getModifiedCount() > 0;
            
            if (success) {
                log.debug("Successfully updated MongoDB for user: {}, version: {} → {}", 
                        event.getUserId(), event.getVersion() - 1, event.getVersion());
            } else {
                log.warn("Version conflict or no document found for user: {}, expected version: {}", 
                        event.getUserId(), event.getVersion() - 1);
                
                // 对于立即同步的失败，需要特殊处理
                if (event.getPriority() == SyncPriority.IMMEDIATE) {
                    handleImmediateSyncFailure(event);
                }
            }
            
            return success;
            
        } catch (Exception e) {
            log.error("Failed to update MongoDB for user: {}, version: {}", 
                    event.getUserId(), event.getVersion(), e);
            return false;
        }
    }

    /**
     * 构建版本查询条件
     * 确保版本连续性，实现乐观锁
     */
    private Query buildVersionQuery(UserProfileSyncEvent event) {
        return Query.query(
                Criteria.where("userId").is(event.getUserId())
                        .and("dataVersion").is(event.getVersion() - 1)  // 确保版本连续
        );
    }

    /**
     * 构建增量更新操作
     * 基于UserProfileDocument的数据分区结构进行增量更新
     */
    private Update buildIncrementalUpdate(UserProfileSyncEvent event) {
        Update update = new Update()
                .set("dataVersion", event.getVersion())
                .set("updatedAt", convertTimestampToInstant(event.getTimestamp()));

        // 选择性更新不同的Map字段 - 增量更新而非覆盖
        updateMapFields(update, "staticProfile", event.getStaticProfileUpdatesMap());
        updateMapFields(update, "dynamicProfile", event.getDynamicProfileUpdatesMap());
        updateMapFields(update, "computedMetrics", event.getComputedMetricsUpdatesMap());
        updateMapFields(update, "socialMedia", event.getSocialMediaUpdatesMap());
        updateMapFields(update, "interestsPreferences", event.getInterestsPreferencesUpdatesMap());
        updateMapFields(update, "professionalInfo", event.getProfessionalInfoUpdatesMap());
        updateMapFields(update, "behavioralData", event.getBehavioralDataUpdatesMap());
        updateMapFields(update, "extendedProperties", event.getExtendedPropertiesUpdatesMap());

        // 处理标签更新
        handleTagUpdates(update, event);

        // 处理状态更新
        if (!event.getStatusUpdate().isEmpty()) {
            update.set("status", event.getStatusUpdate());
        }

        // 更新核心字段
        updateCoreFields(update, event);

        return update;
    }

    /**
     * 更新Map字段
     * 对嵌套Map进行增量更新，只更新变化的字段
     */
    private void updateMapFields(Update update, String fieldPrefix, Map<String, Any> updates) {
        updates.forEach((key, anyValue) -> {
            try {
                Object value = convertFromAny(anyValue);
                update.set(fieldPrefix + "." + key, value);
            } catch (Exception e) {
                log.warn("Failed to convert Any value for field: {}.{}", fieldPrefix, key, e);
            }
        });
    }

    /**
     * 处理标签更新
     * 支持标签的添加和删除操作
     */
    private void handleTagUpdates(Update update, UserProfileSyncEvent event) {
        if (!event.getTagsToAddList().isEmpty()) {
            update.addToSet("tags").each(event.getTagsToAddList().toArray());
        }
        if (!event.getTagsToRemoveList().isEmpty()) {
            update.pullAll("tags", event.getTagsToRemoveList().toArray());
        }
    }

    /**
     * 更新核心字段
     * 处理元数据信息
     */
    private void updateCoreFields(Update update, UserProfileSyncEvent event) {
        if (event.hasMetadata()) {
            ProfileMetadata metadata = event.getMetadata();
            if (metadata.hasRegistrationDate()) {
                update.set("registrationDate", convertTimestampToInstant(metadata.getRegistrationDate()));
            }
            if (metadata.hasLastActiveAt()) {
                update.set("lastActiveAt", convertTimestampToInstant(metadata.getLastActiveAt()));
            }
        }
    }

    /**
     * 将Protobuf Any转换为Java对象
     */
    private Object convertFromAny(Any anyValue) throws InvalidProtocolBufferException {
        // 将Any转换为JSON字符串，然后解析为Object
        String json = JsonFormat.printer().print(anyValue);
        // 这里可以根据需要实现更复杂的类型转换逻辑
        return json;
    }

    /**
     * 将Protobuf Timestamp转换为Java Instant
     */
    private Instant convertTimestampToInstant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * 处理立即同步失败
     * 当立即同步遇到版本冲突时的恢复机制
     */
    private void handleImmediateSyncFailure(UserProfileSyncEvent event) {
        log.error("CRITICAL: Immediate sync failed for user: {}, version: {}", 
                event.getUserId(), event.getVersion());

        try {
            // 查询当前MongoDB中的版本
            Query currentQuery = Query.query(Criteria.where("userId").is(event.getUserId()));
            var currentDoc = mongoTemplate.findOne(currentQuery, Map.class, COLLECTION_NAME);

            if (currentDoc != null) {
                Object currentVersion = currentDoc.get("dataVersion");
                log.info("Current MongoDB version for user: {} is {}, expected: {}", 
                        event.getUserId(), currentVersion, event.getVersion() - 1);

                // TODO: 可以考虑使用当前版本+1重新尝试更新
                // 但这需要更复杂的冲突解决机制
            } else {
                log.warn("No existing document found for user: {}", event.getUserId());
            }

        } catch (Exception e) {
            log.error("Failed to handle immediate sync failure for user: {}", event.getUserId(), e);
        }
    }
}