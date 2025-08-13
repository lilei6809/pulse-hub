package com.pulsehub.profileservice.sync.converter;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.pulsehub.common.proto.*;
import com.pulsehub.profileservice.document.UserProfileDocument;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * UserProfileSyncEvent 到 UserProfileDocument 转换器
 * 
 * 用于将 Kafka 消息中的 UserProfileSyncEvent Protobuf 对象
 * 转换为适用于 MongoDB 存储的 UserProfileDocument 对象
 * 
 * 支持两种同步模式：
 * 1. 完整同步：完全替换用户画像数据
 * 2. 增量同步：基于字段路径的精确更新
 */
@Component
public class UserProfileSyncEventConverter {

    /**
     * 将 UserProfileSyncEvent 转换为 UserProfileDocument
     * 
     * @param syncEvent Protobuf 同步事件
     * @return MongoDB 文档对象
     */
    public UserProfileDocument convert(UserProfileSyncEvent syncEvent) {
        if (syncEvent == null || syncEvent.getUserId() == null || syncEvent.getUserId().isEmpty()) {
            throw new IllegalArgumentException("UserProfileSyncEvent 或 userId 不能为空");
        }

        switch (syncEvent.getSyncType()) {
            case FULL_SYNC:
                return convertFromFullSync(syncEvent);
            case INCREMENTAL_SYNC:
                return convertFromIncrementalSync(syncEvent);
            default:
                throw new IllegalArgumentException("不支持的同步类型: " + syncEvent.getSyncType());
        }
    }

    /**
     * 完整同步转换
     */
    private UserProfileDocument convertFromFullSync(UserProfileSyncEvent syncEvent) {
        FullProfileSync fullSync = syncEvent.getFullSync();
        
        UserProfileDocument document = UserProfileDocument.builder()
                .userId(syncEvent.getUserId())
                .dataVersion(extractDataVersion(syncEvent))
                .build();

        // 转换核心字段
        if (fullSync.hasCoreFields()) {
            convertCoreFields(fullSync.getCoreFields(), document);
        }

        // 转换各种画像数据
        document.setStaticProfile(convertStructToMap(fullSync.getStaticProfile()));
        document.setDynamicProfile(convertStructToMap(fullSync.getDynamicProfile()));
        document.setComputedMetrics(convertStructToMap(fullSync.getComputedMetrics()));
        document.setSocialMedia(convertStructToMap(fullSync.getSocialMedia()));
        document.setInterestsPreferences(convertStructToMap(fullSync.getInterestsPreferences()));
        document.setProfessionalInfo(convertStructToMap(fullSync.getProfessionalInfo()));
        document.setBehavioralData(convertStructToMap(fullSync.getBehavioralData()));
        document.setExtendedProperties(convertStructToMap(fullSync.getExtendedProperties()));

        // 更新时间戳
        document.updateTimestamp();
        
        return document;
    }

    /**
     * 增量同步转换
     * 
     * 注意：增量同步需要基于现有的 UserProfileDocument 进行更新
     * 这里返回一个包含更新信息的新文档，实际应用中需要先查询现有文档再应用更新
     */
    private UserProfileDocument convertFromIncrementalSync(UserProfileSyncEvent syncEvent) {
        IncrementalSync incrementalSync = syncEvent.getIncrementalSync();
        
        // 创建一个用于承载增量更新的文档
        UserProfileDocument document = UserProfileDocument.builder()
                .userId(syncEvent.getUserId())
                .dataVersion(extractDataVersion(syncEvent))
                .build();

        // 应用字段更新
        for (FieldUpdate fieldUpdate : incrementalSync.getFieldUpdatesList()) {
            applyFieldUpdate(document, fieldUpdate);
        }

        document.updateTimestamp();
        return document;
    }

    /**
     * 转换核心字段
     */
    private void convertCoreFields(CoreProfileFields coreFields, UserProfileDocument document) {
        if (coreFields.hasCreatedAt()) {
            document.setCreatedAt(convertTimestamp(coreFields.getCreatedAt()));
        }
        
        if (coreFields.hasUpdatedAt()) {
            document.setUpdatedAt(convertTimestamp(coreFields.getUpdatedAt()));
        }
        
        if (!coreFields.getDataVersion().isEmpty()) {
            document.setDataVersion(coreFields.getDataVersion());
        }
        
        if (coreFields.hasRegistrationDate()) {
            document.setRegistrationDate(convertTimestamp(coreFields.getRegistrationDate()));
        }
        
        if (coreFields.hasLastActiveAt()) {
            document.setLastActiveAt(convertTimestamp(coreFields.getLastActiveAt()));
        }
        
        if (!coreFields.getStatus().isEmpty()) {
            document.setStatus(coreFields.getStatus());
        }
        
        if (!coreFields.getTagsList().isEmpty()) {
            Set<String> tags = new HashSet<>(coreFields.getTagsList());
            document.setTags(tags);
        }
    }

    /**
     * 应用字段更新（增量同步使用）
     */
    private void applyFieldUpdate(UserProfileDocument document, FieldUpdate fieldUpdate) {
        String fieldPath = fieldUpdate.getFieldPath();
        Value newValue = fieldUpdate.getNewValue();
        UpdateOperation operation = fieldUpdate.getOperation();

        // 解析字段路径
        String[] pathSegments = fieldPath.split("\\.");
        
        if (pathSegments.length < 2) {
            throw new IllegalArgumentException("无效的字段路径: " + fieldPath);
        }

        String section = pathSegments[0];  // 例如：core_fields, dynamic_profile
        String fieldName = pathSegments[1]; // 例如：status, page_view_count

        switch (section) {
            case "core_fields":
                applyCoreFieldUpdate(document, fieldName, newValue, operation);
                break;
            case "static_profile":
                applyMapFieldUpdate(document.getStaticProfile(), document::setStaticProfile, fieldName, newValue, operation);
                break;
            case "dynamic_profile":
                applyMapFieldUpdate(document.getDynamicProfile(), document::setDynamicProfile, fieldName, newValue, operation);
                break;
            case "computed_metrics":
                applyMapFieldUpdate(document.getComputedMetrics(), document::setComputedMetrics, fieldName, newValue, operation);
                break;
            case "social_media":
                applyMapFieldUpdate(document.getSocialMedia(), document::setSocialMedia, fieldName, newValue, operation);
                break;
            case "interests_preferences":
                applyMapFieldUpdate(document.getInterestsPreferences(), document::setInterestsPreferences, fieldName, newValue, operation);
                break;
            case "professional_info":
                applyMapFieldUpdate(document.getProfessionalInfo(), document::setProfessionalInfo, fieldName, newValue, operation);
                break;
            case "behavioral_data":
                applyMapFieldUpdate(document.getBehavioralData(), document::setBehavioralData, fieldName, newValue, operation);
                break;
            case "extended_properties":
                applyMapFieldUpdate(document.getExtendedProperties(), document::setExtendedProperties, fieldName, newValue, operation);
                break;
            default:
                throw new IllegalArgumentException("不支持的字段段: " + section);
        }
    }

    /**
     * 应用核心字段更新
     */
    private void applyCoreFieldUpdate(UserProfileDocument document, String fieldName, Value newValue, UpdateOperation operation) {
        switch (fieldName) {
            case "status":
                if (operation == UpdateOperation.SET) {
                    document.setStatus(newValue.getStringValue());
                }
                break;
            case "data_version":
                if (operation == UpdateOperation.SET) {
                    document.setDataVersion(newValue.getStringValue());
                }
                break;
            case "last_active_at":
                if (operation == UpdateOperation.SET && newValue.hasStringValue()) {
                    document.setLastActiveAt(Instant.parse(newValue.getStringValue()));
                }
                break;
            // 可以根据需要添加更多核心字段的处理
            default:
                throw new IllegalArgumentException("不支持的核心字段更新: " + fieldName);
        }
    }

    /**
     * 应用 Map 字段更新
     */
    private void applyMapFieldUpdate(Map<String, Object> currentMap, 
                                   java.util.function.Consumer<Map<String, Object>> setter,
                                   String fieldName, Value newValue, UpdateOperation operation) {
        
        Map<String, Object> targetMap = currentMap != null ? new HashMap<>(currentMap) : new HashMap<>();

        switch (operation) {
            case SET:
                targetMap.put(fieldName, convertProtobufValue(newValue));
                break;
            case INCREMENT:
                Object currentValue = targetMap.get(fieldName);
                if (currentValue instanceof Number && newValue.hasNumberValue()) {
                    double current = ((Number) currentValue).doubleValue();
                    double increment = newValue.getNumberValue();
                    targetMap.put(fieldName, current + increment);
                } else {
                    throw new IllegalArgumentException("INCREMENT 操作只支持数值字段");
                }
                break;
            case REMOVE:
                targetMap.remove(fieldName);
                break;
            case MERGE:
                if (newValue.hasStructValue()) {
                    Object existingValue = targetMap.get(fieldName);
                    if (existingValue instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> existingMap = (Map<String, Object>) existingValue;
                        Map<String, Object> newMap = convertStructToMap(newValue.getStructValue());
                        existingMap.putAll(newMap);
                        targetMap.put(fieldName, existingMap);
                    } else {
                        targetMap.put(fieldName, convertStructToMap(newValue.getStructValue()));
                    }
                } else {
                    throw new IllegalArgumentException("MERGE 操作只支持对象字段");
                }
                break;
            default:
                throw new IllegalArgumentException("不支持的更新操作: " + operation);
        }

        setter.accept(targetMap);
    }

    /**
     * 将 Protobuf Struct 转换为 Java Map
     */
    private Map<String, Object> convertStructToMap(Struct struct) {
        if (struct == null || struct.getFieldsMap().isEmpty()) {
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
            result.put(entry.getKey(), convertProtobufValue(entry.getValue()));
        }
        return result;
    }

    /**
     * 将 Protobuf Value 转换为 Java Object
     */
    private Object convertProtobufValue(Value value) {
        switch (value.getKindCase()) {
            case NULL_VALUE:
                return null;
            case NUMBER_VALUE:
                return value.getNumberValue();
            case STRING_VALUE:
                return value.getStringValue();
            case BOOL_VALUE:
                return value.getBoolValue();
            case STRUCT_VALUE:
                return convertStructToMap(value.getStructValue());
            case LIST_VALUE:
                return value.getListValue().getValuesList().stream()
                        .map(this::convertProtobufValue)
                        .toList();
            default:
                return value.toString();
        }
    }

    /**
     * 将 Protobuf Timestamp 转换为 Java Instant
     */
    private Instant convertTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * 从同步事件中提取数据版本
     */
    private String extractDataVersion(UserProfileSyncEvent syncEvent) {
        if (syncEvent.hasMetadata() && !syncEvent.getMetadata().getDataVersion().isEmpty()) {
            return syncEvent.getMetadata().getDataVersion();
        }
        
        // 如果元数据中没有版本信息，使用事件时间戳作为版本
        if (syncEvent.hasTimestamp()) {
            return String.valueOf(syncEvent.getTimestamp().getSeconds());
        }
        
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    /**
     * 创建用于增量更新的部分文档
     * 
     * @param syncEvent 同步事件
     * @return 包含更新信息的 UserProfileDocument，需要与现有文档合并
     */
    public UserProfileDocument createIncrementalUpdateDocument(UserProfileSyncEvent syncEvent) {
        if (syncEvent.getSyncType() != SyncType.INCREMENTAL_SYNC) {
            throw new IllegalArgumentException("只支持增量同步事件");
        }

        return convertFromIncrementalSync(syncEvent);
    }

    /**
     * 验证同步事件的有效性
     */
    public boolean isValidSyncEvent(UserProfileSyncEvent syncEvent) {
        if (syncEvent == null) {
            return false;
        }

        if (syncEvent.getUserId() == null || syncEvent.getUserId().isEmpty()) {
            return false;
        }

        if (syncEvent.getSyncType() == SyncType.SYNC_TYPE_UNSPECIFIED) {
            return false;
        }

        switch (syncEvent.getSyncType()) {
            case FULL_SYNC:
                return syncEvent.hasFullSync();
            case INCREMENTAL_SYNC:
                return syncEvent.hasIncrementalSync() && 
                       !syncEvent.getIncrementalSync().getFieldUpdatesList().isEmpty();
            default:
                return false;
        }
    }
}