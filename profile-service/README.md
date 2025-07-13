# 用户档案服务 (Profile Service)

## 功能描述

用户档案服务负责管理用户的核心信息和行为数据，是PulseHub平台的重要组成部分。主要功能包括：

- **用户档案管理**: 创建、查询、更新和删除用户档案
- **缓存策略**: 使用Redis实现高效的数据缓存，提供多种缓存策略
  - 基于注解的缓存 (@Cacheable, @CacheEvict等)
  - 手动缓存管理
- **REST API**: 提供标准化的REST接口供其他服务和客户端使用
- **数据持久化**: 将用户档案存储在PostgreSQL数据库中
- **服务发现集成**: 通过服务发现机制与其他服务交互

该服务在整个系统中作为用户数据的权威来源，提供高性能的数据访问和管理能力。

## 端口信息

- **默认端口**: 8084 (可通过`PROFILE_SERVICE_PORT`环境变量配置)
- **API端点**: 
  - 创建用户档案: POST http://localhost:8084/api/profiles
  - 查询用户档案: GET http://localhost:8084/api/profiles/{id}
  - 更新用户档案: PUT http://localhost:8084/api/profiles/{id}
  - 删除用户档案: DELETE http://localhost:8084/api/profiles/{id}
- **健康检查**: http://localhost:8084/actuator/health

## 调用链关系

用户档案服务在系统架构中的位置：

```
[Web/Mobile Clients] ─────────┐
                              v
[API Gateway] ───────> [Profile Service] ──注册──> [Discovery Service]
                              |
                              ├──读写──> [PostgreSQL Database]
                              │
                              └──缓存──> [Redis Cache]
```

### 上游调用者

- **API网关**: 路由外部请求到Profile Service
- **其他微服务**: 通过服务发现机制调用用户档案服务获取用户信息

### 下游依赖

- **PostgreSQL**: 用于持久化用户档案数据
- **Redis**: 用于缓存频繁访问的用户数据
- **服务发现中心**: 用于注册自身服务

### REST API示例

创建用户档案:
```http
POST /api/profiles HTTP/1.1
Host: localhost:8084
Content-Type: application/json

{
  "userId": "user123",
  "email": "user@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "preferences": {
    "theme": "dark",
    "language": "en"
  }
}
```

查询用户档案:
```http
GET /api/profiles/user123 HTTP/1.1
Host: localhost:8084
```

## 环境变量配置

| 环境变量名 | 描述 | 默认值 |
|------------|------|--------|
| PROFILE_SERVICE_PORT | 服务运行端口 | 8084 |
| POSTGRES_HOST | PostgreSQL主机地址 | localhost (本地) / postgres (Docker) |
| POSTGRES_PORT | PostgreSQL端口 | 5432 |
| POSTGRES_DB | PostgreSQL数据库名 | pulsehub |
| POSTGRES_USER | PostgreSQL用户名 | pulsehub |
| POSTGRES_PASSWORD | PostgreSQL密码 | pulsehub |
| REDIS_HOST | Redis主机地址 | localhost (本地) / redis (Docker) |
| REDIS_PORT | Redis端口 | 6379 |
| EUREKA_URI | 服务发现地址 | http://localhost:8761/eureka | 