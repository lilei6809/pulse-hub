# Data Sync Service - Event Router Implementation

## 概览

Data Sync Service 现已实现基于**Kafka Streams**的智能事件路由器，支持混合同步策略，确保关键业务数据的实时性和普通数据的高效批量处理。

## 🚀 核心特性

### 1. Kafka Streams 事件路由器
- **优先级路由**: 根据`SyncPriority`自动将事件路由到不同Topic
- **exactly_once_v2**: 保证事件不重复路由，确保数据一致性
- **实时处理**: 禁用缓存，保证毫秒级路由延迟
- **userId分区**: 确保同用户事件的顺序性

### 2. 双Topic架构
```
profile-sync-events → EventRouter → immediate-sync-events (4分区)
                                  → batch-sync-events (8分区)
```

### 3. 双消费者组策略
- **immediate-sync-group**: 2并发, 单条处理, 低延迟
- **batch-sync-group**: 5并发, 批量处理, 高吞吐

### 4. 重试降级机制
- 立即同步失败3次后自动降级到批量队列
- 版本冲突检测和恢复
- 完整的监控指标和告警

## 📊 数据流架构

```
profile-sync-events → EventRouter → immediate-sync-events → ImmediateConsumer → MongoDB
                                  → batch-sync-events → BatchConsumer → MongoDB
                                           ↑
                                    (降级后的立即同步事件)
```

## 🔧 关键组件

### EventRouter (事件路由器)
- 路径: `com.pulsehub.datasync.router.EventRouter`
- 功能: Kafka Streams拓扑构建，优先级路由逻辑

### ImmediateSyncConsumer (立即同步消费者)
- 路径: `com.pulsehub.datasync.consumer.ImmediateSyncConsumer`
- 功能: 高优先级事件处理，重试降级机制

### BatchSyncConsumer (批量同步消费者)  
- 路径: `com.pulsehub.datasync.consumer.BatchSyncConsumer`
- 功能: 批量事件处理，容错性处理

### MongoProfileUpdater (MongoDB更新器)
- 路径: `com.pulsehub.datasync.service.MongoProfileUpdater`  
- 功能: 增量更新，版本控制，乐观锁

## ⚙️ 配置说明

### Kafka Streams配置
```yaml
spring:
  kafka:
    streams:
      application-id: data-sync-router
      properties:
        processing.guarantee: exactly_once_v2
        num.stream.threads: 1
        cache.max.bytes.buffering: 0
```

### 消费者组配置
```yaml
spring:
  kafka:
    consumer:
      immediate-sync-group:
        group-id: immediate-sync-group
        max-poll-records: 1        # 单条处理
        concurrency: 2             # 2个并发消费者
      batch-sync-group:
        group-id: batch-sync-group
        max-poll-records: 10       # 批量处理
        concurrency: 5             # 5个并发消费者
```

### Topic配置
```yaml
sync:
  topics:
    profile-sync: "profile-sync-events"
    immediate-sync: "immediate-sync-events"    # 4分区
    batch-sync: "batch-sync-events"            # 8分区
```

## 📈 监控指标

- `immediate.sync.success` - 立即同步成功次数
- `immediate.sync.fallback` - 立即同步降级次数  
- `batch.sync.success` - 批量同步成功次数
- `batch.sync.failure` - 批量同步失败次数
- `immediate.sync.duration` - 立即同步处理时间
- `batch.sync.duration` - 批量同步处理时间

## 🧪 测试策略

### 单元测试
- `EventRouterTest`: 路由逻辑测试
- `MongoProfileUpdaterTest`: MongoDB更新逻辑测试
- `ImmediateSyncConsumerTest`: 立即同步处理测试

### 集成测试  
- `EventRoutingIntegrationTest`: 端到端流程测试

## 🛠 开发指南

### 运行测试
```bash
mvn test -pl data-sync-service
```

### 构建服务
```bash
mvn clean install -pl data-sync-service
```

### Docker运行
```bash
docker-compose up data-sync-service --build
```

## 📋 Common Redis组件

注意: 在 `com/pulsehub/common/redis` 包下有 Redis 相关的基础组件:

```
com/pulsehub/common/redis/
├── ProfileOperationResult.java     # Redis操作结果封装
├── RedisDistributedLock.java      # 分布式锁实现
├── RedisKeyUtils.java             # Redis键名工具
├── RedisProfileData.java          # Redis用户资料数据模型
├── RedissonConfig.java            # Redisson配置
└── RedisUserProfileOperations.java # Redis用户资料操作
```

## 🔄 未来扩展

- [ ] 多实例分片处理机制
- [ ] Dead Letter Queue (DLQ) 完善
- [ ] 实时监控Dashboard
- [ ] 自动扩缩容支持


