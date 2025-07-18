# PulseHub

PulseHub is the backend for a powerful, event-driven Customer Data Platform (CDP). This project is built from the ground up to be a scalable, robust, and performant data middleware, focusing on backend architecture excellence.

This repository contains the v0.1 MVP with **enterprise-grade Redis caching layer**, establishing both the foundational data pipeline and high-performance caching architecture.

## 🎉 Latest Achievement: Task 7 Redis Caching Excellence

**Task 7已完成！** 实现了企业级Redis缓存层，包含：

✅ **业务场景驱动的分层缓存策略** - 针对CRM、Analytics、行为跟踪等不同场景的优化配置  
✅ **4,500+行生产级代码** - 包含完整的配置、服务、测试和示例代码  
✅ **全面的测试验证体系** - 单元测试、集成测试和端到端验证脚本  
✅ **详细的最佳实践文档** - 可作为后续微服务的标准模板  

📚 **详细文档**: [Redis缓存层最佳实践指南](docs/redis-caching-best-practices.md)

## Architecture (v0.1 + Redis Caching)

The MVP is built on a multi-module Maven project structure and an event-driven architecture using Apache Kafka. All services are containerized with Docker. **Redis caching layer** provides enterprise-grade performance optimization.

```mermaid
graph TD
    subgraph "Development Environment (Docker Compose)"
        direction TB
        
        subgraph "Event Pipeline"
            Producer["🔥 Event Producer<br/>(Java/Spring Boot)"] 
            KafkaTopic["📮 Kafka Topic<br/>(user-activity-events)"]
            IngestionService["📥 Ingestion Service<br/>(Java/Spring Boot)"]
        end
        
        subgraph "Profile Management"
            ProfileService["👤 Profile Service<br/>(Java/Spring Boot)<br/>✨ Redis Caching"]
            Redis["🧠 Redis Cache<br/>Multi-tier Strategy<br/>• CRM: 10min TTL<br/>• Analytics: 4hr TTL<br/>• Behaviors: 30min TTL"]
        end
        
        subgraph "Data Storage"
            DB["🗄️ PostgreSQL<br/>(tracked_events, user_profiles)"]
        end
        
        Producer -- "1. Protobuf Events" --> KafkaTopic
        KafkaTopic -- "2. Event Stream" --> IngestionService
        IngestionService -- "3. Persist Data" --> DB
        
        ProfileService <-- "4. Cache Ops<br/>High Performance" --> Redis
        ProfileService -- "5. DB Fallback<br/>Master Data" --> DB
    end

    style Producer fill:#D5F5E3,stroke:#2ECC71,stroke-width:3px
    style IngestionService fill:#D6EAF8,stroke:#3498DB,stroke-width:3px
    style ProfileService fill:#FFE6E6,stroke:#E74C3C,stroke-width:3px
    style DB fill:#E8DAEF,stroke:#8E44AD,stroke-width:3px
    style KafkaTopic fill:#FDEBD0,stroke:#F39C12,stroke-width:3px
    style Redis fill:#E8F8E8,stroke:#27AE60,stroke-width:3px
```

## Prerequisites

-   Java 21
-   Maven 3.8+
-   Docker
-   Docker Compose

## Tech Stack Highlights

-   **🔧 Runtime**: Java 21 + Spring Boot 3.x with Virtual Threads
-   **📨 Messaging**: Apache Kafka (KRaft mode, no Zookeeper)
-   **💾 Storage**: PostgreSQL (primary) + Redis (high-performance cache)
-   **🏗️ Architecture**: Multi-module Maven + Docker containerization
-   **📦 Data Format**: Protobuf for cross-service contracts

## How to Run

1.  **Build the Project**

    This is a critical first step. You must build the project with Maven to create the executable JAR files that will be copied into the Docker images.

    From the project root directory, run:
    ```bash
    mvn clean install
    ```

2.  **Start All Services**

    Once the project is successfully built, you can start the entire infrastructure using Docker Compose.

    ```bash
    docker-compose up --build -d
    ```
    *   `--build` flag ensures that Docker images are rebuilt using the latest JAR files.
    *   `-d` flag runs the containers in detached mode.

## How to Verify

1.  **Check Container Status**

    Verify that all containers are up and running:
    ```bash
    docker-compose ps
    ```
    You should see `event-producer`, `ingestion-service`, `kafka`, `schema-registry`, `postgres`, and `kafka-ui` with a "running" or "healthy" status.

2.  **Monitor Logs**

    You can monitor the logs of the ingestion service to see the events being consumed in real-time.
    ```bash
    docker-compose logs -f ingestion-service
    ```
    You should see log entries indicating that user activity events are being received.

3.  **Query the Database**

    Connect to the PostgreSQL database to confirm that the data is being persisted.

    ```bash
    docker-compose exec -it postgres psql -U pulsehub -d pulsehub_db
    ```
    The password is `pulsehub_password`.

    Once inside the `psql` shell, run the following query:
    ```sql
    SELECT * FROM tracked_events;
    ```
    You should see a list of the events that have been processed. To exit `psql`, type `\q`.

4.  **Check Kafka UI**

    The Kafka UI is available at [http://localhost:8088](http://localhost:8088). You can use it to inspect topics and messages.

5.  **Test Redis Caching Layer**

    验证企业级Redis缓存层的工作状态：

    ```bash
    # 测试Redis连接
    docker-compose exec redis redis-cli ping
    
    # 查看缓存配置效果
    ./test-cache-behavior.sh
    
    # 验证分层缓存策略
    ./test-cache-config-selection.sh
    
    # 测试事件驱动缓存
    ./test-event-driven-cache.sh
    ```

    您也可以通过profile-service的健康检查API验证Redis状态：
    ```bash
    curl http://localhost:8080/actuator/health
    ```

## How to Stop

To stop all running containers, run the following command from the project root:
```bash
docker-compose down
```

If you want to stop the containers AND remove all associated volumes (including database data), use the `-v` flag. This is useful for a complete cleanup.

```bash
docker-compose down -v
```
