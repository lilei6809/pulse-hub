# 事件生产者服务 (Event Producer)

## 功能描述

事件生产者服务负责生成和发送用户活动事件到Kafka消息队列，用于模拟用户在系统中的行为。主要功能包括：

- **事件生成**: 通过EventGenerator类生成模拟用户活动事件
- **Protobuf序列化**: 使用Protocol Buffers (protobuf)高效序列化事件数据
- **Kafka消息发送**: 将序列化的事件发送到指定的Kafka主题
- **连续模拟**: 持续不断地生成和发送事件，模拟真实用户活动流

该服务在开发和测试环境中特别有用，可以持续生成事件数据流，用于测试数据摄取和处理管道。

## 端口信息

- **默认端口**: 8082 (可通过`server.port`配置)
- **健康检查**: http://localhost:8082/actuator/health

## 调用链关系

事件生产者服务在数据流中处于源头位置：

```
[Event Producer] ──注册──> [Discovery Service]
[Event Producer] ──发送事件──> [Kafka Topic: user-activity-events]
                                    |
                                    v
                           [Ingestion Service]
```

### 上游依赖

- **服务发现中心**: 用于注册自身服务
- **Kafka**: 用于发布事件消息
- **Schema Registry**: 用于存储和验证Protobuf模式

### 下游服务

- **摄取服务 (Ingestion Service)**: 消费Event Producer生成的事件

### 调用示例

```java
// 服务内部生成并发送事件
UserActivityEvent event = EventGenerator.generateEvent();
kafkaProducerService.sendEvent(event);
```

## 数据流说明

1. 服务启动后通过CommandLineRunner持续生成事件
2. 事件通过Protobuf序列化为紧凑的二进制格式
3. 序列化后的消息发送到Kafka主题"user-activity-events"
4. 下游的Ingestion Service从Kafka主题中消费这些事件

## 环境变量配置

| 环境变量名 | 描述 | 默认值 |
|------------|------|--------|
| SPRING_PROFILES_ACTIVE | 激活的环境配置 | local |
| KAFKA_BOOTSTRAP_SERVERS | Kafka服务器地址 | localhost:9092 (本地) / kafka:29092 (Docker) |
| SCHEMA_REGISTRY_URL | Schema Registry地址 | http://localhost:8081 (本地) / http://schema-registry:8081 (Docker) |
| EUREKA_URI | 服务发现地址 | http://localhost:8761/eureka |