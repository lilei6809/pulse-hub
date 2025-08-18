

## **业务需求**

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

## **最佳方案设计**

### **1. 整体架构选择**

**选用方案：分片处理（Sharding）+ 定时任务**

```
Redis (dirty_flags)
    ↓ (分片扫描)
DocSyncProducer (多实例)
    ↓ (Kafka发送)
Kafka Topics
    ↓ (消费处理)
Consumer → MongoDB
```

### **2. 多实例协调机制**

### **分片策略**

```
// 用户分片逻辑
private int getUserShard(String userId) {
    return Math.abs(userId.hashCode()) % totalInstances;
}

// 实例配置
@Value("${app.instance.id}") // A, B, C
private String instanceId;

@Value("${app.instance.total}") // 3
private int totalInstances;
```

### **任务分配示例**

```
假设 3 个实例处理 dirty_flags = {user:100, user:201, user:302, user:403}

分片计算：
- user:100 → hash % 3 = 1 → 实例B处理
- user:201 → hash % 3 = 2 → 实例C处理
- user:302 → hash % 3 = 0 → 实例A处理
- user:403 → hash % 3 = 1 → 实例B处理

执行结果：
- 实例A: [user:302]
- 实例B: [user:100, user:403]
- 实例C: [user:201]
```

### **3. 数据同步流程**

### **Dirty Flag 机制**

```
Redis 数据结构：
- user:123:profile = {name: "张三", age: 25, version: 5}
- dirty_flags = Set{user:123, user:456, user:789}

工作流程：
1. 用户资料更新 → 添加 userId 到 dirty_flags
2. DocSyncProducer 扫描 → 获取属于当前分片的 dirty flags
3. 发送到 Kafka → 发送成功后删除对应的 dirty flag
```

### **版本控制机制**

```
// MongoDB 更新时的版本检查
db.userProfiles.updateOne(
  {
    userId: "123",
    version: currentVersion  // 只有版本匹配才更新
  },
  {
    $set: { ...updateData, version: currentVersion + 1 }
  }
)
```

### **4. 错误处理策略**

### **重试机制**

```
@Retryable(
    value = {Exception.class},
    maxAttempts = 3,
    backoff = @Backoff(
        delay = 1000,        // 初始延迟1秒
        multiplier = 2.0,    // 指数退避：1s, 2s, 4s
        maxDelay = 10000     // 最大延迟10秒
    )
)
public void sendToKafka(String userId, ProfileData data) {
    kafkaTemplate.send("profile-sync", data);
}
```

### **失败处理流程**

```
发送失败 → 重试 3 次（指数退避）→ 仍失败 → 发送到 DLQ + 保留 dirty_flag → 继续处理下一个用户
```

### **DLQ 消息格式**

```
public class DLQMessage {
    private String userId;
    private ProfileData originalData;
    private ErrorContext errorContext;
}

public class ErrorContext {
    private String errorMessage;
    private String stackTrace;
    private int retryCount;
    private LocalDateTime failureTimestamp;
    private String instanceId;
    private String kafkaTopic;
}
```

### **5. 核心代码结构**

```
@Component
public class DocSyncProducer {

    @Value("${app.instance.id:A}")
    private String instanceId;

    @Value("${app.instance.total:3}")
    private int totalInstances;

    @Scheduled(fixedDelayString = "${sync.interval:60000}")
    public void processDirtyFlags() {
        String lockKey = "sync_lock_" + instanceId;

        if (redisLock.tryLock(lockKey, 30, TimeUnit.SECONDS)) {
            try {
                int shardIndex = getShardIndex(instanceId);
                Set<String> dirtyUsers = scanDirtyFlagsForShard(shardIndex);

                for (String userId : dirtyUsers) {
                    processUser(userId);
                }
            } finally {
                redisLock.unlock(lockKey);
            }
        }
    }

    private void processUser(String userId) {
        try {
            ProfileData data = getProfileFromRedis(userId);
            sendToKafka(userId, data);
            cleanupDirtyFlag(userId);
        } catch (Exception e) {
            // 重试机制由 @Retryable 处理
            // 最终失败会进入 DLQ
            handleFailure(userId, e);
        }
    }
}
```

## **方案优势**

### **1. 架构优势**

- **负载均衡**：通过分片确保任务均匀分配
- **故障隔离**：单个用户处理失败不影响其他用户
- **简单可靠**：避免了复杂的分布式协调机制

### **2. 运维优势**

- **易于理解**：分片逻辑清晰，容易调试
- **监控友好**：每个实例处理独立分片，便于监控
- **配置简单**：通过配置文件控制实例数量和同步间隔

### **3. 扩展策略**

- **短期**：通过增加实例数量实现水平扩展
- **中长期**：必要时可升级为一致性哈希或动态分片机制

## **配置说明**

```
# application.yml
app:
  instance:
    id: A              # 实例标识：A, B, C
    total: 3           # 总实例数量

sync:
  interval: 60000      # 同步间隔（毫秒）

spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      retries: 0       # 禁用 Kafka 自带重试，使用 @Retryable
```

## **监控指标**

### **关键指标**

- **处理速度**：每分钟处理的 dirty flags 数量
- **错误率**：发送失败率和 DLQ 消息数量
- **分片均衡性**：各实例处理的任务数量分布
- **处理延迟**：从 dirty flag 创建到处理完成的时间

### **告警规则**

- DLQ 消息数量超过阈值
- 某个分片长时间无法处理
- 实例宕机检测

# **方案最终确认**

1. 业务背景确认
   - 场景：是否主要针对高频更新的动态资料（如行为数据、活动轨迹）？
     - 对. 对于一些特别重要的静态资料, 比如用户的 VIP 状态那些, 需要现在考虑吗? 请你给我建议
   - 时效性：同步延迟在分钟级别是否可接受？
     - 就我们目前需要处理的 events 来说可以接受. 如果需要即刻的查询, 可以同步查询 mongodb + redis + postgresql 来组成最新的 profile

● 好的，让我们逐项确认。对于第1项业务背景：

1. 关于VIP状态等重要静态资料的建议

   基于你的描述和现有架构，我建议分层处理策略：

   立即同步 vs 批量同步

   - VIP状态、权限变更等关键静态资料：应该立即同步，不走 DocSyncProducer
   - 行为数据、活动轨迹等动态资料：走 DocSyncProducer 批量同步

具体实现建议

```java
  // 在现有 profile-service 中区分处理
  public class ProfileUpdateService {
	  // 关键静态资料 - 立即同步
	  public void updateCriticalProfile(String userId, CriticalProfileData data) {
	      // 1. 更新 PostgreSQL
	      staticProfileRepository.save(data);
	      // 2. 立即发送到 Kafka (高优先级topic)
	      kafkaTemplate.send("profile-critical-sync", data);
	      // 3. 更新 Redis 缓存
	      redisTemplate.opsForValue().set(getCacheKey(userId), data);
	  }
	
	  // 动态资料 - 标记 dirty flag
	  public void updateDynamicProfile(String userId, DynamicProfileData data) {
	      // 1. 更新 Redis
	      redisTemplate.opsForValue().set(getDynamicKey(userId), data);
	      // 2. 添加到 dirty flags (由 DocSyncProducer 处理)
	      redisTemplate.opsForSet().add("dirty_flags", userId);
	  }
```

分类标准建议

立即同步（关键静态资料）：

- VIP状态变更
- 权限级别变更
- 账户状态变更（封禁/解封）
- 重要的偏好设置变更

批量同步（动态资料）：

- 浏览行为、点击轨迹
- 活动参与记录
- 实时统计数据（访问次数等）
- 临时性标签

好处

1. 保证关键数据一致性：重要变更立即生效
2. 优化性能：高频动态数据批量处理，减少系统压力
3. 架构清晰：不同重要性的数据走不同路径
4. 时效性确认 ✅

分钟级延迟可接受，通过 mongodb + redis + postgresql 组合查询获取最新状态的方案很合理。

------

你的建议(我们都要实现):

1. 消息格式统一建议

   建议使用统一的消息格式，而不是为静态/动态同步创建不同的 Protobuf：

   // 统一的用户资料同步消息 message UserProfileSyncEvent { string user_id = 1; SyncType sync_type = 2;  // STATIC_IMMEDIATE, DYNAMIC_BATCH map<string, google.protobuf.Any> profile_data = 3; int64 version = 4; int64 timestamp = 5;

   ```
     enum SyncType {
         STATIC_IMMEDIATE = 0;
         DYNAMIC_BATCH = 1;
     }
   ```

   }

   好处：

   - 消费端逻辑统一
   - 减少 Protobuf 维护成本
   - 通过 sync_type 区分处理优先级

2. 错误处理简化建议

   MVP 阶段建议简化错误处理：

   // 简单的重试 + 日志记录 @Retryable(maxAttempts = 3) public void syncToKafka(UserProfileSyncEvent event) { try { kafkaTemplate.send(getTopicName(event.getSyncType()), event); } catch (Exception e) { log.error("Profile sync failed for user: {}, type: {}", event.getUserId(), event.getSyncType(), e); // MVP: 只记录错误，不实现复杂的 DLQ 机制 } }

3. 版本控制优化建议

   利用现有的 UserProfileDocument.dataVersion 字段：

   // 在 MongoDB 更新时使用乐观锁 public boolean updateUserProfile(UserProfileSyncEvent event) { Query query = Query.query( Criteria.where("userId").is(event.getUserId()) .and("dataVersion").is(event.getVersion())  // 版本匹配 );

   ```
     Update update = new Update()
         .set("updatedAt", Instant.now())
         .inc("dataVersion", 1);  // 版本自增
   
     // 根据 sync_type 更新不同字段
     if (event.getSyncType() == STATIC_IMMEDIATE) {
         update.set("staticProfile", event.getProfileDataMap());
     } else {
         update.set("dynamicProfile", event.getProfileDataMap());
     }
   
     UpdateResult result = mongoTemplate.updateFirst(query, update, UserProfileDocument.class);
     return result.getModifiedCount() > 0;  // 返回是否成功更新
   ```

   }

4. 架构扩展性建议

   考虑到未来的扩展，建议在设计时预留接口：

   // 抽象同步策略，便于后续扩展 public interface ProfileSyncStrategy { void sync(String userId, Map<String, Object> profileData); }

   @Component public class ImmediateSyncStrategy implements ProfileSyncStrategy { // 静态资料立即同步实现 }

   @Component public class BatchSyncStrategy implements ProfileSyncStrategy { // 动态资料批量同步实现 }

5. 配置集中化建议

   建议在 common 模块中定义同步相关的常量：

   public class SyncConstants { public static final String TOPIC_PROFILE_STATIC = "profile-static-sync"; public static final String TOPIC_PROFILE_DYNAMIC = "profile-dynamic-sync"; public static final String DIRTY_FLAGS_KEY = "profile:dirty_flags"; public static final int DEFAULT_SYNC_INTERVAL = 60000; // 1分钟 }

6. 监控预留建议

   即使 MVP 不实现复杂监控，也建议预留接口：

   @Component public class SyncMetricsCollector { private final MeterRegistry meterRegistry;

   ```
     public void recordSyncSuccess(SyncType type) {
         // 预留指标收集点
         Counter.builder("profile.sync.success")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
     }
   ```

   }

# 任务分解与进度:

- Update Todos
  - [x] 确认 MVP 范围和实现策略
  - [ ] 在 infrastructure-service 中添加新的 Kafka Topics 配置
  - [ ] 创建 data-sync-service 基础项目结构
  - [ ] 实现静态资料立即同步功能
  - [ ] 实现动态资料批量同步功能 (DocSyncProducer 单实例版本)
  - [ ] 创建同步消息的 Protobuf 定义
  - [ ] 实现 MongoDB 消费者处理逻辑
  - [ ] 编写单元测试和集成测试
  - [ ] 配置 Docker Compose 集成
  - [ ] 验证完整的数据流和提交代码

1. MVP范围确定  按你的建议来

2. data-sync-service 我希望的是要负责所有的不同系统间的数据同步工作, 集中管理, 然后 profile-service 需要调用 data-sync-service 实现数据同步. 重新划分两个服务的责任界限.

3. 问题3: 数据一致性保证

     文档中提到使用 dataVersion 做乐观锁，但如果 Redis 中的动态数据和 MongoDB 中的数据版本不一致怎么处理？

   这是我之前没有考虑到的问题, 如果使用 dataVersion 作为乐观锁, 那就需要在 redis 保存 update 前, 获取当前 userdocument 中的 dataVersion, 再对其进行增量操作.
   对此你有什么好的建议吗?



解决方案总结

  1. MVP范围确定

  分两个阶段实现：
  - 第一阶段: 基础data-sync-service + 动态资料批量同步 + 统一消息格式 + MongoDB消费者
  - 第二阶段: 静态资料立即同步 + 多实例分片 + 完整错误处理

  2. 服务责任重新划分

  data-sync-service (数据同步中心):
  - 负责所有系统间的数据同步工作
  - 提供两个API接口：立即同步和批量同步标记
  - 管理Kafka消息发送、版本控制、重试机制
  - 运行DocSyncProducer定时任务处理批量同步

  profile-service (业务逻辑专注):
  - 专注于用户资料的业务操作
  - 更新数据后调用data-sync-service的API接口
  - 不再直接处理Kafka消息和版本管理
  - 通过Feign Client与data-sync-service通信

  3. 版本一致性解决方案 (推荐方案B)

  核心思路: 由data-sync-service统一管理版本，简化复杂性

  工作流程:
  1. profile-service更新Redis数据后，调用data-sync-service标记dirty flag
  2. DocSyncProducer定时扫描dirty flags时：

    - 从Redis获取最新用户数据
    - 从MongoDB获取当前版本号
    - 创建版本递增的同步消息发送到Kafka
  3. MongoDB消费者使用乐观锁更新：

    - 检查版本号是否连续
    - 版本冲突时记录日志但不重试（最终一致性）

  优势:
  - 版本管理集中，逻辑清晰
  - Redis不需要存储额外版本元数据，节省内存
  - 容错性好，通过MongoDB乐观锁处理版本冲突
  - MVP实现简单，后续可扩展

  这个方案避免了在Redis中维护复杂的版本信息，而是在同步时动态获取版本，既保证了数据一致性又简化了实现复杂度。