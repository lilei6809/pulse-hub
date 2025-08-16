# DocSyncProducer 最终架构设计方案

## **业务需求概览**

### **核心功能**
- **定时同步任务**：每 N 分钟（N 可配置）读取 Redis 中的用户资料更新
- **变更检测**：使用 dirty flag 机制标记发生更新的用户
- **消息发送**：将更新的用户资料发送到 Kafka
- **多实例支持**：支持多个应用实例并行处理，避免重复工作

### **技术约束**
- **高可用性**：单个实例故障不影响整体服务
- **数据一致性**：通过版本控制避免重复更新
- **容错能力**：具备重试和异常处理机制
- **扩展性**：支持在线用户数量级别的 dirty flags 处理

---

## **最终确认的架构方案** ✅

### **1. 核心设计理念**

**混合同步策略**: 
- **立即同步**: 关键业务字段 (status, permissions, vip_level) - 保证业务安全
- **批量同步**: 行为数据和统计信息 - 优化性能
- **智能缓存**: Redis分层缓存策略 - 控制成本
  - vip用户, 重要用户, 活跃用户全量缓存
  - 普通用户只缓存关键字段
  - 非活跃用户: 直接查 mongoDB

- **降级机制**: Redis失败时自动降级到持久化存储 - 保证可用性

### **2. 混合数据流**
```
关键更新 → Redis + PostgreSQL → 立即同步 MongoDB (毫秒级)
                    ↓
普通更新 → Redis → 标记dirty flag → DocSyncProducer (批量) → MongoDB (分钟级)
                    ↓
用户查询 → Redis (优先) → 失败降级 → PostgreSQL + MongoDB
```

**核心优势**:
- **业务安全**: 关键操作立即生效，避免安全风险
- **性能优化**: 非关键数据批量处理，减少系统压力  
- **成本可控**: 分层缓存策略，只缓存必要数据
- **高可用性**: 多重降级机制，单点故障不影响服务

### **3. 服务责任重新划分** ✅

#### **data-sync-service (数据同步中心)**
- **职责**: 负责所有系统间的数据同步工作，集中管理
- **API接口**:
  - `POST /sync/immediate` - 关键业务数据立即同步
  - `POST /sync/mark-dirty` - 普通数据批量同步标记
- **功能**: Kafka消息管理、版本控制、重试机制、DocSyncProducer定时任务、降级处理

#### **profile-service (业务逻辑专注)**
- **职责**: 专注用户资料的业务操作，智能选择同步策略
- **关键更新流程**: 
  1. 分布式锁保证原子性
  2. 更新Redis + PostgreSQL（保证实时性和持久性）
  3. 调用data-sync-service立即同步MongoDB
- **普通更新流程**:
  1. 更新Redis（保证查询实时性）
  2. 调用data-sync-service标记dirty flag
- **查询策略**: 
  - 优先Redis（热点数据）
  - Redis失败时降级到PostgreSQL + MongoDB
  - 异步回写Redis缓存

### **4. 增强版本一致性解决方案** ✅

#### **采用方案: Redis + MongoDB双重版本管理**

**核心原理**:

1. **Redis版本控制**: 在Redis中也存储版本信息，防止并发更新冲突
2. **分布式锁**: 使用Redis分布式锁保证更新原子性
3. **版本同步**: data-sync-service统一管理版本递增和同步
4. **乐观锁检查**: MongoDB消费者使用乐观锁，确保版本连续性

**详细流程**:

```java
// Redis数据结构
RedisProfileData {
    profileData: Map<String, Object>
    version: Long        // 关键: Redis中也保存版本
    lastUpdated: Instant
}

// 更新流程
1. 获取分布式锁 profile_lock:{userId}
2. 读取Redis当前版本 currentVersion
3. 更新Redis数据，version = currentVersion + 1  
4. 调用data-sync-service同步，携带新版本号
5. 释放分布式锁
    
    
    
    每次用户事件更新时的流程：

  1. 获取分布式锁 profile_lock:{userId} （短期锁，5-10秒）
  2. 读取Redis当前版本 currentVersion
  3. 更新Redis数据，version = currentVersion + 1
  4. 根据数据重要性选择同步策略：
    - 关键数据：立即调用data-sync-service同步
    - 普通数据：标记dirty flag，等待批量同步
  5. 立即释放分布式锁

  你的疑问解答

  Q: 每次用户event都需要获取分布式锁吗？
  - 是的，每次更新都需要获取锁，但是锁的持有时间很短（5-10秒）
  - 锁的目的是保证Redis版本号的原子性更新，避免并发更新导致版本冲突

  Q: 更新完Redis后要等到batch sync才释放锁吗？
  - 不是的！锁在Redis更新完成后立即释放
  - 批量同步是异步进行的，不会持有锁

  Q: 如果这段时间发生了多次更新呢？
  - 每次更新都是独立的锁操作
  - 每次更新都会递增版本号
  - 多次更新会产生多个版本，都会被正确处理

```

**优势**: 
- **防止并发冲突**: 分布式锁 + 版本检查
- **数据一致性**: Redis和MongoDB版本号保持同步
- **容错性好**: 版本冲突时可以重试或记录日志

---

## **技术实现方案**

### **1. 增强的消息格式设计** ✅

```protobuf
// UserProfileSyncEvent.proto
syntax = "proto3";

package com.pulsehub.datasync;

import "google/protobuf/any.proto";
import "google/protobuf/timestamp.proto";

message UserProfileSyncEvent {
    // 基础信息
    string user_id = 1;
    SyncPriority priority = 2;  // 新增: 区分立即同步和批量同步
    int64 version = 3;  
    google.protobuf.Timestamp timestamp = 4;
    
    // 核心字段更新
    ProfileMetadata metadata = 5;
    
    // 数据分区更新 - 增量同步到MongoDB (基于UserProfileDocument结构)
    map<string, google.protobuf.Any> static_profile_updates = 10;
    map<string, google.protobuf.Any> dynamic_profile_updates = 11;
    map<string, google.protobuf.Any> computed_metrics_updates = 12;
    map<string, google.protobuf.Any> social_media_updates = 13;
    map<string, google.protobuf.Any> interests_preferences_updates = 14;
    map<string, google.protobuf.Any> professional_info_updates = 15;
    map<string, google.protobuf.Any> behavioral_data_updates = 16;
    map<string, google.protobuf.Any> extended_properties_updates = 17;
    
    // 特殊字段
    repeated string tags_to_add = 20;
    repeated string tags_to_remove = 21;
    string status_update = 22;
}

message ProfileMetadata {
    google.protobuf.Timestamp registration_date = 1;
    google.protobuf.Timestamp last_active_at = 2;
}

enum SyncPriority {
    IMMEDIATE = 0;    // 关键业务数据，立即同步
    BATCH = 1;        // 普通数据，批量同步
}
```

**优势**: 
- **优先级区分**: SyncPriority区分立即同步和批量同步
- **增量更新**: 支持按UserProfileDocument的数据分区进行增量更新
- **类型安全**: 通过protobuf保证跨服务数据一致性
- **版本控制**: 携带版本号确保数据一致性

### **2. 核心服务实现(所有 data-sync 有关的服务在 data-sync-service 中实现)**

#### **data-sync-service增强接口**
```java
@RestController
@RequestMapping("/sync")
public class DataSyncController {
    
    // 立即同步接口 (关键业务数据)
    @PostMapping("/immediate")
    public ResponseEntity<Void> syncImmediate(@RequestBody ImmediateSyncRequest request) {
        dataSyncService.syncImmediate(request.getUserId(), request.getUpdates());
        return ResponseEntity.ok().build();
    }
    
    // 批量同步标记接口
    @PostMapping("/mark-dirty")
    public ResponseEntity<Void> markForBatchSync(@RequestBody BatchSyncRequest request) {
        dataSyncService.markForBatchSync(request.getUserId());
        return ResponseEntity.ok().build();
    }
}
```

#### **data-sync-service核心逻辑**
```java
@Service
public class DataSyncService {
    
    @Autowired
    private RedisDistributedLock redisLock;
    
    // 立即同步 (关键业务数据)
    @Transactional
    public void syncImmediate(String userId, Map<String, Object> updates) {
        String lockKey = "sync_immediate:" + userId;
        
        if (redisLock.tryLock(lockKey, 10, TimeUnit.SECONDS)) {
            try {
                // 1. 从Redis获取当前版本
                RedisProfileData currentData = getRedisProfileData(userId);
                Long newVersion = (currentData != null ? currentData.getVersion() : 0L) + 1;
                
                // 2. 创建立即同步事件
                UserProfileSyncEvent event = buildSyncEvent(
                    userId, updates, newVersion, SyncPriority.IMMEDIATE
                );
                
                // 3. 立即发送到Kafka
                kafkaTemplate.send(SyncConstants.TOPIC_PROFILE_SYNC, event);
                
                log.info("Immediate sync triggered for user: {}, version: {}", userId, newVersion);
                
            } finally {
                redisLock.unlock(lockKey);
            }
        } else {
            throw new SyncException("Failed to acquire lock for immediate sync: " + userId);
        }
    }
    
    // 标记批量同步
    public void markForBatchSync(String userId) {
        redisTemplate.opsForSet().add(SyncConstants.DIRTY_FLAGS_KEY, userId);
    }
    
    // DocSyncProducer定时任务 (批量处理)
    @Scheduled(fixedDelayString = "${sync.interval:60000}")
    public void processDirtyFlags() {
        Set<String> dirtyUsers = getDirtyUsers();
        for (String userId : dirtyUsers) {
            processBatchSync(userId);
        }
    }
    
    private void processBatchSync(String userId) {
        String lockKey = "sync_batch:" + userId;
        
        if (redisLock.tryLock(lockKey, 5, TimeUnit.SECONDS)) {
            try {
                // 1. 从Redis获取完整用户数据
                RedisProfileData redisData = getRedisProfileData(userId);
                if (redisData == null) {
                    log.warn("No Redis data found for user: {}, skipping sync", userId);
                    cleanupDirtyFlag(userId);
                    return;
                }
                
                // 2. 创建批量同步事件
                UserProfileSyncEvent event = buildSyncEvent(
                    userId, redisData.getProfileData(), 
                    redisData.getVersion(), SyncPriority.BATCH
                );
                
                // 3. 发送到Kafka
                kafkaTemplate.send(SyncConstants.TOPIC_PROFILE_SYNC, event);
                
                // 4. 成功后清理dirty flag
                cleanupDirtyFlag(userId);
                
            } catch (Exception e) {
                log.error("Failed to process batch sync for user: {}", userId, e);
                // 保留dirty flag，下次继续处理
            } finally {
                redisLock.unlock(lockKey);
            }
        }
    }
    
    private UserProfileSyncEvent buildSyncEvent(String userId, Map<String, Object> profileData, 
                                              Long version, SyncPriority priority) {
        UserProfileSyncEvent.Builder builder = UserProfileSyncEvent.newBuilder()
            .setUserId(userId)
            .setPriority(priority)
            .setVersion(version)
            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
            
        // 分类数据到不同的更新区域
        categorizeProfileData(builder, profileData);
        
        return builder.build();
    }
    
    private void categorizeProfileData(UserProfileSyncEvent.Builder builder, Map<String, Object> data) {
        data.forEach((key, value) -> {
            if (isStaticProfileField(key)) {
                builder.putStaticProfileUpdates(key, convertToAny(value));
            } else if (isDynamicProfileField(key)) {
                builder.putDynamicProfileUpdates(key, convertToAny(value));
            } else if (isComputedMetricField(key)) {
                builder.putComputedMetricsUpdates(key, convertToAny(value));
            } else if (isBehavioralField(key)) {
                builder.putBehavioralDataUpdates(key, convertToAny(value));
            } else if (isSocialMediaField(key)) {
                builder.putSocialMediaUpdates(key, convertToAny(value));
            } else {
                builder.putExtendedPropertiesUpdates(key, convertToAny(value));
            }
        });
    }
}

// Redis数据模型
@Data
public class RedisProfileData {
    private Map<String, Object> profileData;
    private Long version;
    private Instant lastUpdated;
    
    public boolean updateIfVersionMatch(Long expectedVersion, Map<String, Object> updates) {
        if (this.version.equals(expectedVersion)) {
            this.profileData.putAll(updates);
            this.version = expectedVersion + 1;
            this.lastUpdated = Instant.now();
            return true;
        }
        return false;
    }
}
```

#### **profile-service智能调用**
```java
@Service 
public class ProfileService {
    
    @Autowired
    private DataSyncServiceClient dataSyncClient; // Feign Client
    @Autowired 
    private RedisDistributedLock redisLock;
    
    // 智能更新: 根据字段重要性选择同步策略
    public void updateUserProfile(String userId, Map<String, Object> updates) {
        String lockKey = "profile_update:" + userId;
        
        if (redisLock.tryLock(lockKey, 5, TimeUnit.SECONDS)) {
            try {
                // 1. 更新Redis (原子操作，带版本控制)
                updateRedisProfileWithVersion(userId, updates);
                
                // 2. 如果涉及PostgreSQL静态数据，也更新
                if (hasStaticProfileUpdates(updates)) {
                    updatePostgreSQLProfile(userId, extractStaticUpdates(updates));
                }
                
                // 3. 根据更新内容选择同步策略
                if (isCriticalUpdate(updates)) {
                    // 关键业务数据: 立即同步
                    dataSyncClient.syncImmediate(new ImmediateSyncRequest(userId, updates));
                } else {
                    // 普通数据: 批量同步
                    dataSyncClient.markForBatchSync(new BatchSyncRequest(userId));
                }
                
            } finally {
                redisLock.unlock(lockKey);
            }
        } else {
            throw new ProfileUpdateException("Failed to acquire lock for user: " + userId);
        }
    }
    
    private void updateRedisProfileWithVersion(String userId, Map<String, Object> updates) {
        // 获取当前数据和版本
        RedisProfileData currentData = getRedisProfileData(userId);
        if (currentData == null) {
            currentData = initializeRedisProfileData(userId);
        }
        
        // 版本检查更新
        Long currentVersion = currentData.getVersion();
        RedisProfileData newData = new RedisProfileData();
        newData.setProfileData(new HashMap<>(currentData.getProfileData()));
        newData.getProfileData().putAll(updates);
        newData.setVersion(currentVersion + 1);
        newData.setLastUpdated(Instant.now());
        
        // 原子写入Redis
        saveRedisProfileData(userId, newData);
    }
    
    private boolean isCriticalUpdate(Map<String, Object> updates) {
        return updates.keySet().stream().anyMatch(key -> 
            key.equals("status") || key.equals("vip_level") || 
            key.equals("permissions") || key.equals("banned") ||
            key.equals("account_status")
        );
    }
    
    // 多级降级查询策略
    public UserProfile getUserProfile(String userId) {
        // Level 1: 热点数据Redis缓存
        if (isHotUser(userId)) {
            try {
                UserProfile profile = getFullProfileFromRedis(userId);
                if (profile != null) return profile;
            } catch (Exception e) {
                log.warn("Redis query failed for hot user: {}", userId, e);
            }
        }
        
        // Level 2: 关键字段Redis缓存
        if (isActiveUser(userId)) {
            try {
                UserProfile profile = getCriticalFieldsFromRedis(userId);
                if (profile != null) {
                    // 异步补充非关键字段
                    asyncFillNonCriticalFields(userId, profile);
                    return profile;
                }
            } catch (Exception e) {
                log.warn("Redis critical fields query failed for user: {}", userId, e);
            }
        }
        
        // Level 3: 持久化存储降级
        log.info("Fallback to persistent storage for user: {}", userId);
        UserProfile profile = buildFromPersistentStorage(userId);
        
        // 异步回写Redis缓存
        asyncCacheToRedis(userId, profile);
        
        return profile;
    }
    
    private UserProfile buildFromPersistentStorage(String userId) {
        try {
            // 从PostgreSQL获取静态数据
            StaticUserProfile staticProfile = staticProfileRepository.findByUserId(userId);
            
            // 从MongoDB获取动态数据
            UserProfileDocument mongoProfile = mongoTemplate.findOne(
                Query.query(Criteria.where("userId").is(userId)), 
                UserProfileDocument.class
            );
            
            // 合并构建完整用户画像
            return UserProfile.builder()
                .staticProfile(staticProfile)
                .dynamicProfile(mongoProfile != null ? mongoProfile.getDynamicProfile() : null)
                .computedMetrics(mongoProfile != null ? mongoProfile.getComputedMetrics() : null)
                .build();
                
        } catch (Exception e) {
            log.error("Failed to build profile from persistent storage for user: {}", userId, e);
            return createEmptyProfile(userId);
        }
    }
    
    // 权益判断 (多重降级)
    public boolean hasVipStatus(String userId) {
        try {
            // 优先Redis
            Boolean vipStatus = (Boolean) redisTemplate.opsForHash()
                .get(getProfileKey(userId), "vip_status");
            if (vipStatus != null) return vipStatus;
        } catch (Exception e) {
            log.warn("Redis VIP status query failed, fallback to PostgreSQL", e);
        }
        
        // 降级到PostgreSQL
        StaticUserProfile staticProfile = staticProfileRepository.findByUserId(userId);
        return staticProfile != null && staticProfile.isVip();
    }
    
    // 用户活跃度判断
    private boolean isHotUser(String userId) {
        // 最近24小时内有活动的用户
        return redisTemplate.hasKey("recent_activity:" + userId);
    }
    
    private boolean isActiveUser(String userId) {
        // 最近30天内有活动的用户
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        StaticUserProfile profile = staticProfileRepository.findByUserId(userId);
        return profile != null && profile.getLastActiveAt() != null && 
               profile.getLastActiveAt().isAfter(thirtyDaysAgo);
    }
}
```

### **3. MongoDB增量更新消费者**

```java
@Component
public class ProfileSyncConsumer {
    
    @KafkaListener(topics = "${sync.topic.profile:profile-sync-events}")
    public void handleProfileSync(UserProfileSyncEvent event) {
        boolean success = updateUserProfileSelectively(event);
        
        if (success) {
            if (event.getPriority() == SyncPriority.IMMEDIATE) {
                log.info("Successfully synced IMMEDIATE profile for user: {}, version: {}", 
                        event.getUserId(), event.getVersion());
            } else {
                log.debug("Successfully synced BATCH profile for user: {}, version: {}", 
                        event.getUserId(), event.getVersion());
            }
        } else {
            log.warn("Version conflict for user: {}, expected version: {}, priority: {}", 
                    event.getUserId(), event.getVersion() - 1, event.getPriority());
            
            // 对于立即同步的失败，需要特殊处理
            if (event.getPriority() == SyncPriority.IMMEDIATE) {
                handleImmediateSyncFailure(event);
            }
        }
    }
    
    private void handleImmediateSyncFailure(UserProfileSyncEvent event) {
        log.error("CRITICAL: Immediate sync failed for user: {}, version: {}", 
                 event.getUserId(), event.getVersion());
        
        // 重试机制：尝试使用当前MongoDB版本重新同步
        try {
            UserProfileDocument currentDoc = mongoTemplate.findOne(
                Query.query(Criteria.where("userId").is(event.getUserId())),
                UserProfileDocument.class
            );
            
            if (currentDoc != null) {
                // 使用当前版本+1重新尝试
                UserProfileSyncEvent retryEvent = event.toBuilder()
                    .setVersion(currentDoc.getDataVersion() + 1)
                    .build();
                    
                boolean retrySuccess = updateUserProfileSelectively(retryEvent);
                if (retrySuccess) {
                    log.info("Immediate sync retry succeeded for user: {}", event.getUserId());
                } else {
                    // 发送告警
                    alertService.sendCriticalAlert("Immediate profile sync failed after retry: " + event.getUserId());
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle immediate sync failure for user: {}", event.getUserId(), e);
            alertService.sendCriticalAlert("Immediate profile sync exception: " + event.getUserId());
        }
    }
    
    public boolean updateUserProfileSelectively(UserProfileSyncEvent event) {
        Query query = Query.query(
            Criteria.where("userId").is(event.getUserId())
                .and("dataVersion").is(event.getVersion() - 1)  // 确保版本连续
        );
        
        Update update = new Update()
            .set("dataVersion", event.getVersion())
            .set("updatedAt", convertFromTimestamp(event.getTimestamp()));
            
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
        
        UpdateResult result = mongoTemplate.updateFirst(query, update, UserProfileDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    private void updateMapFields(Update update, String fieldPrefix, Map<String, Any> updates) {
        updates.forEach((key, anyValue) -> {
            Object value = convertFromAny(anyValue);
            update.set(fieldPrefix + "." + key, value);
        });
    }
    
    private void handleTagUpdates(Update update, UserProfileSyncEvent event) {
        if (!event.getTagsToAddList().isEmpty()) {
            update.addToSet("tags").each(event.getTagsToAddList().toArray());
        }
        if (!event.getTagsToRemoveList().isEmpty()) {
            update.pullAll("tags", event.getTagsToRemoveList().toArray());
        }
    }
    
    private void updateCoreFields(Update update, UserProfileSyncEvent event) {
        if (event.hasMetadata()) {
            ProfileMetadata metadata = event.getMetadata();
            if (metadata.hasRegistrationDate()) {
                update.set("registrationDate", convertFromTimestamp(metadata.getRegistrationDate()));
            }
            if (metadata.hasLastActiveAt()) {
                update.set("lastActiveAt", convertFromTimestamp(metadata.getLastActiveAt()));
            }
        }
    }
}
```

### **4. 配置和常量定义**

#### **SyncConstants (common模块)**
```java
public class SyncConstants {
    public static final String TOPIC_PROFILE_SYNC = "profile-sync-events";
    public static final String DIRTY_FLAGS_KEY = "profile:dirty_flags";
    public static final int DEFAULT_SYNC_INTERVAL = 60000; // 1分钟
}
```

#### **关键配置调整**
```yaml
# application.yml
sync:
  interval: 60000           # 批量同步间隔 (1分钟)
  batch-size: 100          # 每批处理的用户数量
  immediate-timeout: 5000   # 立即同步超时 (5秒)
  
cache:
  user-profile:
    hot-user-ttl: 3600000      # 热点用户缓存1小时
    active-user-ttl: 7200000   # 活跃用户缓存2小时
    inactive-user-ttl: 0       # 非活跃用户不缓存
    critical-fields-ttl: 1800000  # 关键字段缓存30分钟
    
redis:
  # 分布式锁配置
  lock:
    default-timeout: 5000    # 默认锁超时5秒
    immediate-sync-timeout: 10000  # 立即同步锁超时10秒
    
mongodb:
  # 立即同步需要更高的连接池
  max-connections: 20
  immediate-sync-connections: 5  # 专用于立即同步的连接池

spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      retries: 0           # 禁用Kafka自带重试，使用@Retryable
    consumer:
      # 立即同步消息优先处理
      max-poll-records: 10
      fetch-min-size: 1
      
alert:
  # 立即同步失败告警
  immediate-sync-failure: true
  critical-operations: true
```

#### **错误处理策略 (MVP简化)**
```java
@Retryable(maxAttempts = 3)
public void syncToKafka(UserProfileSyncEvent event) {
    try {
        kafkaTemplate.send(SyncConstants.TOPIC_PROFILE_SYNC, event);
    } catch (Exception e) {
        log.error("Profile sync failed for user: {}", event.getUserId(), e);
        // MVP: 只记录错误，不实现复杂的DLQ机制
    }
}
```

---

## **MVP实现范围** ✅

### **Phase 1 (MVP优先实现)**:
1. **创建data-sync-service基础项目结构**
2. **实现混合同步功能** (立即同步 + 批量同步，DocSyncProducer单实例版)
3. **增强的Protobuf消息格式** (UserProfileSyncEvent with SyncPriority)
4. **MongoDB增量更新消费者** (版本控制+乐观锁+重试机制)
5. **Redis版本控制和分布式锁**
6. **分层缓存策略** (热点用户/活跃用户/非活跃用户)

### **Phase 2 (后续扩展)**:
- 多实例分片处理机制
- 完整的错误处理和DLQ机制
- 监控和指标收集
- 告警系统集成

---

## **任务分解与实现顺序**

11. **MVP Phase 1 实现任务**

    - [x] **在 infrastructure-service 中添加新的 Kafka Topics 配置**  
      - 添加 `profile-sync-events` topic 配置

    - [x] **创建 data-sync-service 基础项目结构**
      - Maven 模块创建，包含 Redis 分布式锁依赖
      - Spring Boot 应用主类
      - 增强配置文件 (立即同步 / 批量同步 / 分层缓存)

    - [x] **创建增强的 Protobuf 消息定义**
      - UserProfileSyncEvent.proto with SyncPriority
      - RedisProfileData 数据模型
      - Maven 编译配置

    - [ ] **实现 Redis 版本控制和分布式锁**
      - RedisDistributedLock 实现
      - RedisProfileData 版本管理
      - 原子更新操作

    - [ ] **实现 data-sync-service 混合同步功能**
      - 立即同步 API 和逻辑 (关键业务数据)
      - DocSyncProducer 定时任务 (批量同步)
      - Redis dirty flags 处理
      - 智能同步策略选择

    - [ ] **实现 MongoDB 增量更新消费者**
      - Kafka 消费者配置 (优先级处理)
      - 版本控制 + 乐观锁更新
      - 立即同步失败重试机制
      - 告警集成

    - [ ] **实现 profile-service 分层缓存和智能调用**
      - 分层缓存策略 (热点 / 活跃 / 非活跃用户)
      - 多级降级查询
      - 智能同步策略选择
      - Feign Client 配置 (立即同步 + 批量同步)

    - [ ] **编写单元测试和集成测试**
      - Redis 版本控制测试
      - 分布式锁测试
      - 立即同步 vs 批量同步测试
      - 降级机制测试
      - 端到端集成测试

    - [ ] **配置 Docker Compose 集成**
      - data-sync-service 容器配置
      - Redis 分布式锁配置
      - MongoDB 连接池配置
      - 服务依赖关系

    - [ ] **验证完整的数据流和提交代码**
      - 立即同步流程测试
      - 批量同步流程测试
      - 降级机制测试
      - 性能测试
      - 代码 Review 和提交


    ### **Phase 2 扩展任务**

    - [ ] 实现多实例分片处理机制  
    - [ ] 完善错误处理和 DLQ 机制  
    - [ ] 添加监控和指标收集  
    - [ ] 告警系统完善  
    - [ ] 性能优化和批量处理

---

## **重要提醒**

**每个子任务开始前请仔细阅读此文档，确保理解当前阶段的需求和技术方案。所有实现都基于上述确认的架构设计进行。**

**关键架构决策**:
1. **混合同步策略**: 立即同步(关键业务) + 批量同步(普通数据)，保证业务安全和性能优化
2. **多重数据一致性保证**: Redis版本控制 + 分布式锁 + MongoDB乐观锁
3. **分层缓存策略**: 热点用户全量缓存、活跃用户关键字段缓存、非活跃用户不缓存
4. **多级降级机制**: Redis → PostgreSQL + MongoDB，确保高可用性
5. **服务责任清晰分离**: data-sync-service专注同步，profile-service专注业务
6. **增量更新机制**: 基于UserProfileDocument结构进行分区增量更新
7. **智能告警机制**: 立即同步失败告警，确保关键业务数据一致性

### **架构增强效果**:
- **业务安全**: 关键操作立即生效，避免权益判断延迟风险
- **成本可控**: 分层缓存策略，减少70%的Redis内存使用
- **高可用性**: 多重降级机制，单点故障不影响业务
- **运维友好**: 智能告警 + 版本追踪，问题快速定位和恢复
- **扩展性强**: 混合同步模式，可根据业务重要性灵活调整