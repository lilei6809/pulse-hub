# 服务发现中心 (Discovery Service)

## 功能描述

服务发现中心基于Netflix Eureka实现，为PulseHub平台提供以下关键功能：

- **服务注册**: 各微服务启动时自动注册到服务发现中心
- **服务发现**: 允许微服务之间互相发现并通信，无需硬编码服务地址
- **负载均衡**: 在多实例环境下提供基础的负载均衡支持
- **健康检查**: 监控服务实例状态，自动剔除不健康的实例

本服务是整个PulseHub平台的核心基础设施，使得所有其他服务能够在不同环境(本地开发、Docker容器)中无缝通信。

## 端口信息

- **默认端口**: 8761 (可通过`DISCOVERY_SERVICE_PORT`环境变量配置)
- **管理界面**: http://localhost:8761
- **服务注册端点**: http://localhost:8761/eureka
- **健康检查**: http://localhost:8761/actuator/health

## 调用链关系

服务发现中心是基础设施，其他所有服务都依赖它实现互相发现和通信：

```
[所有微服务] ──注册──> [服务发现中心]
[所有微服务] ──发现──> [服务发现中心] ──返回服务列表──> [所有微服务]
```

### 被调用关系

- **服务注册**：所有微服务启动时向服务发现中心注册自己
- **服务查询**：服务需要调用其他服务时，先查询服务发现中心获取目标服务地址

### 调用示例

通过`DiscoveryClientService`获取其他服务实例：

```java
// 获取Profile服务的URI
Optional<URI> profileServiceUri = discoveryClientService.getServiceUri("profile-service");
profileServiceUri.ifPresent(uri -> {
    // 使用URI构建请求
    String profileEndpoint = uri + "/api/profiles/123";
    // 发送请求...
});
```

## 环境变量配置

| 环境变量名 | 描述 | 默认值 |
|------------|------|--------|
| DISCOVERY_SERVICE_PORT | 服务运行端口 | 8761 |
| SPRING_PROFILES_ACTIVE | 激活的环境配置 | default |
| EUREKA_INSTANCE_IP | Docker环境中使用的实例IP | discovery-service | 