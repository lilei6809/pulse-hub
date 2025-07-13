# 数据摄取服务 (Ingestion Service)

## 功能描述

数据摄取服务是PulseHub平台的数据入口，负责从Kafka消费事件数据并进行初步处理和持久化。主要功能包括：

- **事件消费**: 从Kafka主题消费用户活动事件
- **Protobuf反序列化**: 将二进制格式的Protobuf消息反序列化为Java对象
- **数据转换**: 通过EventMapper将事件转换为数据库实体
- **数据持久化**: 将处理后的事件存储到PostgreSQL数据库
- **数据验证**: 确保数据的完整性和一致性

该服务在数据处理流水线中处于前端位置，为后续的数据分析和处理提供可靠的数据源。

## 端口信息

- **默认端口**: 8083 (可通过`INGESTION_SERVICE_PORT`环境变量配置)
- **健康检查**: http://localhost:8083/actuator/health

## 调用链关系

数据摄取服务在数据流中的位置：

```
[Event Producer] ──发送事件──> [Kafka Topic: user-activity-events]
                                     |
                                     v
[Ingestion Service] <──消费事件────┘
        |
        |──注册──> [Discovery Service]
        |
        └──持久化──> [PostgreSQL Database]
```

### 上游依赖

- **Kafka**: 数据源，消费用户活动事件
- **Schema Registry**: 用于Protobuf模式验证
- **服务发现中心**: 用于注册自身服务

### 下游依赖

- **PostgreSQL**: 用于持久化处理后的事件数据

### 数据流

1. 从Kafka主题"user-activity-events"消费事件消息
2. 使用Protobuf反序列化消息
3. 通过EventMapper将事件转换为TrackedEvent实体
4. 使用TrackedEventRepository将实体保存到数据库
5. 处理完成，等待下一条消息

## 环境变量配置

| 环境变量名 | 描述 | 默认值 |
|------------|------|--------|
| INGESTION_SERVICE_PORT | 服务运行端口 | 8083 |
| POSTGRES_HOST | PostgreSQL主机地址 | localhost (本地) / postgres (Docker) |
| POSTGRES_PORT | PostgreSQL端口 | 5432 |
| POSTGRES_DB | PostgreSQL数据库名 | pulsehub |
| POSTGRES_USER | PostgreSQL用户名 | pulsehub |
| POSTGRES_PASSWORD | PostgreSQL密码 | pulsehub |
| KAFKA_BOOTSTRAP_SERVERS | Kafka服务器地址 | localhost:9092 (本地) / kafka:29092 (Docker) |
| SCHEMA_REGISTRY_URL | Schema Registry地址 | http://localhost:8081 (本地) / http://schema-registry:8081 (Docker) |
| EUREKA_URI | 服务发现地址 | http://localhost:8761/eureka | 