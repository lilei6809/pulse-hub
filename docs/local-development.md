# PulseHub 本地开发环境设置

本文档详细说明如何在本地开发环境中运行 PulseHub 服务，同时连接到 Docker 中的基础设施组件。

## 环境准备

### 1. 启动基础设施组件

首先，启动所有基础设施组件（Kafka、Redis、PostgreSQL等）：

```bash
# 在项目根目录执行
docker-compose up -d kafka redis postgres schema-registry discovery-service
```

确认所有容器健康状态：

```bash
docker-compose ps
```

### 2. 配置本地服务

有两种方法可以让本地服务连接到 Docker 中的组件：

#### 方法一：使用本地配置文件（推荐）

每个服务都有一个 `application-local.yml` 配置文件，已经配置好了通过 localhost 访问 Docker 中的组件。

启动服务时，只需使用 `local` profile：

```bash
# Windows
./run-local.bat

# Linux/Mac
./run-local.sh
```

或者手动指定 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

#### 方法二：修改 hosts 文件

如果您希望直接使用服务名访问 Docker 容器，可以修改本地 hosts 文件：

1. 以管理员权限编辑 hosts 文件：
   - Windows: `C:\Windows\System32\drivers\etc\hosts`
   - Linux/Mac: `/etc/hosts`

2. 添加以下映射：
   ```
   127.0.0.1    kafka
   127.0.0.1    redis
   127.0.0.1    postgres
   127.0.0.1    discovery-service
   127.0.0.1    schema-registry
   ```

## 端口映射参考

| 服务 | Docker内部端口 | 宿主机映射端口 |
|------|--------------|--------------|
| Kafka | 29092 | 9092 |
| Redis | 6379 | 6379 |
| PostgreSQL | 5432 | 5432 |
| Discovery Service | 8761 | 8761 |
| Schema Registry | 8081 | 8081 |

## 故障排除

### 连接问题

1. **无法连接到组件**
   - 确认 Docker 容器正在运行：`docker-compose ps`
   - 检查端口映射是否正确：`docker-compose port <service-name> <port>`
   - 尝试使用 `localhost` 而非服务名

2. **服务发现问题**
   - 确认 discovery-service 已启动并健康
   - 检查 Eureka 控制台：http://localhost:8761
   - 确认本地服务的 `eureka.client.serviceUrl.defaultZone` 配置正确

### 日志查看

查看 Docker 容器日志：

```bash
docker-compose logs -f <service-name>
```

查看本地服务日志：在IDE中或查看控制台输出

## 开发工作流

1. 启动基础设施组件（Docker）
2. 启动 discovery-service（Docker 或本地）
3. 启动您正在开发的服务（本地）
4. 进行开发和测试
5. 提交更改前，确保在 Docker 环境中也能正常工作 