# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

PulseHub is an event-driven Customer Data Platform (CDP) backend built on microservices architecture. It focuses on scalable user profile management, real-time event processing, and high-performance caching. The system is designed to handle enterprise-grade data pipelines with Redis caching excellence.

## Common Development Commands

### Build Commands
```bash
# Clean build all modules from project root
mvn clean install

# Build specific service
mvn clean install -pl profile-service

# Skip tests during build
mvn clean install -DskipTests
```

### Docker Commands
```bash
# Start all services in development
docker-compose up --build -d

# View service logs
docker-compose logs -f [service-name]
docker-compose logs -f profile-service
docker-compose logs -f ingestion-service

# Stop all services
docker-compose down

# Full cleanup with volumes
docker-compose down -v
```

### Testing Commands
```bash
# Run tests for specific service
mvn test -pl profile-service

# Run specific test class
mvn test -Dtest=DynamicUserProfileTest -pl profile-service

# Run integration tests
mvn verify -pl profile-service
```

### Database Commands
```bash
# Connect to PostgreSQL
docker-compose exec -it postgres psql -U pulsehub -d pulsehub_db

# Connect to Redis CLI
docker-compose exec redis redis-cli ping
```

### Service Health Checks
```bash
# Check profile service health
curl http://localhost:8084/actuator/health

# Check all service status
docker-compose ps
```

## Architecture Overview

### Core Components
- **Infrastructure Service** (port 8085): Kafka topic management, Redis configuration, system initialization
- **Event Producer** (port 8082): Generates user activity events via Protobuf to Kafka
- **Ingestion Service** (port 8083): Consumes events from Kafka, persists to PostgreSQL
- **Profile Service** (port 8084): User profile management with Redis caching and static/dynamic profile separation
- **Discovery Service** (port 8761): Eureka service registry for microservice discovery

### Technology Stack
- **Runtime**: Java 21 + Spring Boot 3.x with Virtual Threads
- **Messaging**: Apache Kafka (KRaft mode, no Zookeeper)
- **Storage**: PostgreSQL (primary) + Redis (high-performance cache) + mongodb(store completed user-profile and read)
- **Build**: Multi-module Maven project
- **Containerization**: Docker + Docker Compose
- **Data Format**: Protobuf for cross-service contracts

### Data Flow
1. Event Producer → Kafka Topic (user-activity-events)
2. Kafka → Ingestion Service → PostgreSQL (tracked_events)
3. Profile Service ↔ Redis (dynamic profiles) + PostgreSQL (static profiles)

## Key Architectural Patterns

### Profile Management Architecture
The system implements a sophisticated dual-profile architecture:

- **Static Profiles**: Stored in PostgreSQL for persistent user data (demographics, preferences)
- **Dynamic Profiles**: Stored in Redis for high-frequency behavioral data (page views, activity tracking)
- **Caching Strategy**: Multi-tier Redis caching with different TTLs:
  - CRM data: 10min TTL
  - Analytics data: 4hr TTL
  - Behavioral data: 30min TTL

### Service Discovery
All services use Eureka discovery via the `common-config` module:
- Services register with discovery-service on startup
- Inter-service communication uses service names, not hardcoded URLs
- Configuration is environment-aware (local/docker profiles)

### Event-Driven Design
- Protobuf schema for type-safe event contracts
- Kafka for reliable event streaming
- Dead Letter Queue (DLQ) mechanisms for failure handling
- Configurable replication and persistence settings

## Development Guidelines

### Configuration Management
- Use Spring profiles: `local`, `docker`, `test`
- Environment-specific configuration in `application-{profile}.yml`
- No hardcoded URLs for non-local environments
- Configuration injection via `@ConfigurationProperties` pattern

### Redis Usage Patterns
- Key naming: `dynamic_profile:{userId}`, `active_users:recent`
- Atomic operations with Lua scripts for consistency
- TTL-aware indexing for efficient cleanup
- Pipeline operations for bulk updates

### Database Conventions
- PostgreSQL for ACID-compliant persistent data
- JPA entities in `entity` packages
- Repository pattern with Spring Data JPA
- Migration scripts handled via application startup

### Testing Strategy
- Unit tests with JUnit 5
- Integration tests with Testcontainers
- Redis testing with embedded Redis (TestRedisConfig)
- Profile-specific test configurations

### Error Handling
- Retry mechanisms with exponential backoff
- Circuit breaker patterns for external dependencies
- Comprehensive logging with structured output
- Health check endpoints for monitoring

## Module Structure

### Common Modules
- `common/`: Protobuf schemas for cross-service communication
- `common-config/`: Shared service discovery and configuration

### Service Modules
Each service follows standard Maven project structure:
```
src/main/java/com/pulsehub/{service}/
├── {Service}Application.java      # Main application class
├── config/                        # Configuration classes
├── controller/                    # REST endpoints
├── service/                       # Business logic
├── repository/                    # Data access
├── entity/                        # JPA entities
└── domain/                        # Domain models
```

### Key Classes to Understand
- `ProfileServiceApplication.java`: Main entry with caching/scheduling enabled
- `DynamicProfileService.java`: Core Redis-based profile management with TTL cleanup
- `KafkaTopicConfig.java`: Infrastructure topic management with configurable replication
- `DiscoveryClientService.java`: Service registration utilities

## Performance Considerations

### Redis Optimization
- Use Redis pipelines for bulk operations
- Implement proper TTL strategies to prevent memory leaks
- ZSet indexing for efficient range queries (e.g., high-engagement users)
- Lua scripts for atomic multi-key operations

### Kafka Configuration
- Configurable partitions and replication factors
- Producer acknowledgment settings for durability
- Consumer group management for scalability
- Schema registry integration for evolution

### JVM Settings
- Java 21 Virtual Threads enabled for I/O-bound operations
- Configured UTC timezone for consistent time handling
- Spring Boot actuator for monitoring and health checks

## Environment Setup

### Prerequisites
- Java 21 (LTS)
- Maven 3.8+
- Docker & Docker Compose

### Local Development
1. Build project: `mvn clean install`
2. Start infrastructure: `docker-compose up --build -d`
3. Verify health: Check all services via `docker-compose ps`
4. Access Kafka UI: http://localhost:8088
5. Monitor logs: `docker-compose logs -f [service-name]`

### Development Workflow
1. Services auto-register with discovery-service
2. Infrastructure-service initializes Kafka topics and Redis
3. Event-producer generates sample data
4. Ingestion-service processes events to database
5. Profile-service provides API with Redis caching

## Important Notes

- Always build with Maven before Docker operations (JAR files needed for containers)
- Redis TTL cleanup runs hourly via scheduled tasks with distributed locking
- Service startup dependencies managed via Docker Compose health checks
- Use `@EnableDiscoveryClient` for new services requiring service discovery
- Environment variables configured via `.env.docker` for containerized deployments
- 在进行代码设计时, 不要着急, 进行充分的思考, 对于你不确定的问题多使用 web search, 确保你的思路与逻辑的正确.