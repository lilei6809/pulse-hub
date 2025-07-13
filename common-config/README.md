# 公共配置模块 (Common Config)

## 功能描述

公共配置模块是一个共享库，为PulseHub平台中的各个微服务提供通用配置和功能。主要内容包括：

- **服务发现配置**: 提供统一的Eureka客户端配置，使微服务能轻松集成到服务发现体系
- **服务发现客户端**: 封装服务发现和负载均衡功能，简化微服务间的通信
- **公共工具类**: 提供整个系统中通用的工具方法和帮助类
- **共享模型**: 定义在多个服务中使用的数据模型和DTO
- **协议缓冲区定义**: 包含系统中使用的Protocol Buffers消息定义

该模块采用Maven依赖形式，可以被其他服务直接引入，确保整个系统使用一致的配置和工具。

## 使用方式

在微服务的`pom.xml`中添加依赖：

```xml
<dependency>
    <groupId>com.pulsehub</groupId>
    <artifactId>common-config</artifactId>
    <version>${project.version}</version>
</dependency>
```

## 主要组件

### 服务发现配置 (EurekaDiscoveryConfig)

提供标准化的Eureka客户端配置，可以通过属性开启或关闭：

```java
// 在Spring Boot应用中自动装配，无需显式导入
// 可以通过配置禁用：pulsehub.discovery.enabled=false
```

### 服务发现客户端服务 (DiscoveryClientService)

封装服务发现和简单负载均衡逻辑：

```java
@Autowired
private DiscoveryClientService discoveryService;

// 获取服务实例
Optional<URI> serviceUri = discoveryService.getServiceUri("profile-service");
serviceUri.ifPresent(uri -> {
    // 使用服务URI进行通信
});

// 检查服务可用性
boolean isAvailable = discoveryService.isServiceAvailable("profile-service");
```

### Proto定义文件

`src/main/proto`目录中包含Protocol Buffers消息定义，例如：
- `user_activity_event.proto`: 用户活动事件的消息定义

## 调用关系

作为共享库，该模块被所有微服务引用：

```
[Common Config Module]
        ↑
        |
        ├────── [Discovery Service]
        |
        ├────── [Infrastructure Service]
        |
        ├────── [Ingestion Service]
        |
        ├────── [Profile Service]
        |
        └────── [Event Producer]
```

## 注意事项

1. 修改此模块时需谨慎，因为更改会影响所有依赖它的服务
2. 对Proto文件的修改需要重新生成代码并重新构建所有依赖服务
3. 在添加新功能时，确保它们真正是跨服务通用的，避免将特定服务的逻辑放入此模块 