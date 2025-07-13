# 基础设施服务 (Infrastructure Service)

## 功能描述

基础设施服务负责管理和协调PulseHub平台的核心基础组件，确保整个系统的稳定运行。主要功能包括：

- **Kafka主题管理**: 创建、配置和监控平台所需的Kafka主题
- **数据库连接管理**: 验证和监控数据库连接状态
- **Redis缓存管理**: 确保缓存服务正常运行
- **健康检查API**: 提供全面的系统组件健康状态监控
- **基础设施初始化**: 系统启动时进行必要的基础设施初始化

该服务作为平台的"控制塔"，确保所有依赖的基础组件正常运行，为其他服务提供稳定的基础环境。

## 端口信息

- **默认端口**: 8085 (可通过`INFRASTRUCTURE_SERVICE_PORT`环境变量配置)
- **健康检查API**: 
  - 基本检查: http://localhost:8085/api/infrastructure/health
  - 详细检查: http://localhost:8085/api/infrastructure/health/detailed
  - 就绪检查: http://localhost:8085/api/infrastructure/health/ready
- **Actuator端点**: http://localhost:8085/actuator/health

## 调用链关系

基础设施服务主要与基础组件和服务发现中心交互：

```
[Infrastructure Service] ──注册──> [Discovery Service]
[Infrastructure Service] ──创建/管理──> [Kafka Topics]
[Infrastructure Service] ──验证/监控──> [PostgreSQL]
[Infrastructure Service] ──验证/监控──> [Redis]
```

### 被调用关系

- **健康检查API**: 被监控系统或其他服务调用以检查系统状态
- **服务发现**: 被其他服务通过服务发现机制发现并访问

### 调用示例

```java
// 外部服务调用基础设施服务的健康检查API
RestTemplate restTemplate = new RestTemplate();
ResponseEntity<Map> response = restTemplate.getForEntity(
    "http://localhost:8085/api/infrastructure/health/detailed", 
    Map.class
);
Map<String, Object> healthStatus = response.getBody();
```

## 环境变量配置

| 环境变量名 | 描述 | 默认值 |
|------------|------|--------|
| INFRASTRUCTURE_SERVICE_PORT | 服务运行端口 | 8085 |
| POSTGRES_HOST | PostgreSQL主机地址 | postgres (Docker) / localhost (本地) |
| POSTGRES_PORT | PostgreSQL端口 | 5432 |
| POSTGRES_DB | PostgreSQL数据库名 | pulsehub |
| POSTGRES_USER | PostgreSQL用户名 | pulsehub |
| POSTGRES_PASSWORD | PostgreSQL密码 | pulsehub |
| REDIS_HOST | Redis主机地址 | redis (Docker) / localhost (本地) |
| REDIS_PORT | Redis端口 | 6379 |
| KAFKA_BOOTSTRAP_SERVERS | Kafka服务器地址 | kafka:29092 (Docker) / localhost:9092 (本地) |
| EUREKA_URI | 服务发现地址 | http://localhost:8761/eureka |
| APP_ENVIRONMENT | 应用环境 | dev | 