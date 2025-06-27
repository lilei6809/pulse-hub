# PulseHub - Statement of Work (SOW)

*This is a living document and will be updated continuously as the project evolves.*

## 1. Executive Summary & Project Mandate

### 1.1. Project Background
PulseHub is envisioned as the core data middleware for a larger SaaS ecosystem, conceptually similar to the internal data platforms that power companies like HubSpot. In our simulated context, we are the dedicated engineering team responsible for building and maintaining this critical infrastructure. Our "customers" are other internal product teams (e.g., Marketing Hub, Sales Hub) who rely on our platform to provide unified, reliable, and timely customer data.

### 1.2. Business Problem & Value Proposition
In a modern digital business, customer data is generated across numerous touchpoints (websites, mobile apps, backend systems, third-party tools), creating disconnected data silos. This fragmentation prevents a unified understanding of the customer journey, leading to inefficient marketing, missed sales opportunities, and poor customer service.

PulseHub is designed to solve this problem by providing a central nervous system for customer data. Its core value proposition is to:
- **Ingest** data from any source.
- **Identify** and merge customer identities into a single profile.
- **Enrich** profiles with behavioral and transactional data.
- **Segment** audiences based on any attribute or behavior.
- **Activate** this data by syncing it to downstream tools.

### 1.3. Our Mission
Our mission is to build a highly scalable, robust, and performant data middleware. The focus is on **backend architecture excellence**, not UI implementation. All data and user requests will be simulated to allow for a pure focus on system design and engineering.

## 2. Development Philosophy & Methodology

To ensure the project experience is logical, realistic, and mirrors real-world agile development, we will adopt the following structured, iterative methodology:

**Phase 1: Foundation (MVP Construction)**
1.  **Objective:** To build the skeletal architecture of PulseHub. This initial version will establish a basic, end-to-end data pipeline, serving as the foundational infrastructure.
2.  **Scope:** This phase focuses on creating the core components necessary for data to flow through the system (e.g., event producers, a basic ingestion service, messaging queues, and a database), orchestrated via `docker-compose`. Many downstream services will be mocked.
3.  **Outcome:** A runnable, Minimum Viable Product (MVP) representing the state of the system at the moment of our "new hire's" (the user's) onboarding.

**Phase 2: Iterative Development (Ticket-Driven Sprints)**
1.  **Onboarding Simulation:** Once the MVP is complete, we will simulate the user's "official start" as a Mid-level/Senior Middleware Engineer on the team.
2.  **Squad-Based Work:** We will operate as a two-person agile "squad". The AI will act as the Tech Lead/Architect, and the user will be the core developer.
3.  **Ticket-Driven Workflow:** All new features, enhancements, and architectural improvements will be introduced via "tickets" (similar to Jira tickets).
4.  **Development Cycle per Ticket:** For each ticket, we will follow a complete, realistic development lifecycle: Analysis & Design, Implementation, Testing, and Delivery.

This methodology ensures that every piece of code we write is tied to a specific business need and that the PulseHub platform evolves organically, feature by feature, just as it would in a real-world, high-performing tech company.

## 3. Phase 1: The MVP - Laying the Foundation

### 3.1. MVP Goals & Success Criteria
The objective of the MVP is **not** to deliver a feature-rich product, but to **establish a foundational, event-driven architecture** that is stable, observable, and ready for future extension.

**Success of the MVP will be measured by these criteria:**
- [x] **End-to-End Data Flow:** A test event can be produced, published to Kafka, consumed by the `ingestion-service`, and correctly persisted in the PostgreSQL database.
- [x] **"One-Command" Launch:** The entire stack (services, Kafka, database) can be reliably launched with a single `docker-compose up` command.
- [x] **Architectural Readiness:** The codebase is structured as a multi-module Maven project, making it easy to add new services and shared libraries in subsequent phases.
- [x] **Basic Observability:** Service logs are accessible and provide clear information about the events being processed.

### 3.2. MVP Architecture Diagram

```mermaid
graph TD
    subgraph Development Environment [Docker Compose]
        Producer(Event Producer) -- 1. Sends Event --> KafkaTopic[Kafka Topic: user-activity-events];
        KafkaTopic -- 2. Consumed by --> IngestionService(Ingestion Service);
        IngestionService -- 3. Saves Data --> DB[(PostgreSQL)];
    end

    style Producer fill:#D5F5E3,stroke:#2ECC71,stroke-width:2px;
    style IngestionService fill:#D6EAF8,stroke:#3498DB,stroke-width:2px;
    style DB fill:#E8DAEF,stroke:#8E44AD,stroke-width:2px;
    style KafkaTopic fill:#FDEBD0,stroke:#F39C12,stroke-width:2px;
```

### 3.3. MVP Implementation Plan & Tasks

This plan details the one-story-point tasks required to build the MVP.

#### 3.3.1. Project Setup
- [x] Initialize a multi-module Maven project structure (`pulse-hub-parent`, `ingestion-service`, `event-producer`).
- [x] Create the root `pom.xml` to manage dependencies for all sub-modules.

#### 3.3.2. Infrastructure as Code (Docker)
- [x] Create a `docker-compose.yml` file in the project root.
- [x] Add service definitions for `kafka` and `zookeeper`.
- [x] Add a service definition for `postgres`.
- [x] Configure networking and volumes to ensure persistence and communication.

#### 3.3.3. Core Data Model
- [x] Define the `UserActivityEvent.java` POJO in a shared module.
- [x] Create a corresponding JPA Entity `TrackedEvent.java` in the ingestion service.

#### 3.3.4. Ingestion Service (`ingestion-service`)
- [x] Set up the Spring Boot application with necessary dependencies (`web`, `data-jpa`, `kafka`, `postgres-driver`).
- [x] Configure `application.yml` for database and Kafka connections.
- [x] Implement the `KafkaConsumerService` and the `TrackedEventRepository`.
- [x] Implement the core logic to consume, convert, and save events.

#### 3.3.5. Event Producer (`event-producer`)
- [x] Set up a simple application to produce `UserActivityEvent` objects.
- [x] Configure its Kafka connection and implement the sending logic.

#### 3.3.6. Documentation & Finalization
- [x] Update the main `README.md` with instructions on how to run the MVP.
- [x] Perform a full end-to-end test to verify data flow.

---
*Instructions: This SOW.md file should be updated as tasks are completed. Check off the boxes to reflect the current progress of the project.*

## 4. Phase 2: Iterative Development

This phase marks the transition from foundational setup to feature-driven development. Work will be organized into "tickets," simulating an agile sprint workflow.

### 4.1. Ticket #8: Implement User Profile Service

- **Goal:** To create a new `profile-service` responsible for managing unique user profiles, shifting the platform from being event-centric to user-centric.

#### 4.1.1. `profile-service` Standalone Development
- [x] **Structure**: Create the `profile-service` Maven module and directory structure.
- [x] **Configuration**: Create `pom.xml` and basic `application.yml`.
- [x] **Data Layer**: Define the `UserProfile` entity and `UserProfileRepository`.
- [x] **Business Layer**: Implement the `ProfileService` for core logic.
- [x] **API Layer**: Create the `ProfileController` to expose REST endpoints.
- [x] **Containerization**: Create a `Dockerfile` for the service.

#### 4.1.2. System Integration & Verification
- [ ] **Docker Integration**: Add `profile-service` to `docker-compose.yml` with necessary configurations.
- [ ] **Build & Launch Verification**:
    - [ ] Run `mvn clean install` to ensure all modules, including the new service, build successfully.
    - [ ] Run `docker-compose up --build` to verify the `profile-service` can launch and connect to the database without errors.

#### 4.1.3. `ingestion-service` Enhancement
- [ ] **HTTP Client Setup**: Configure a  `WebClient` bean in `ingestion-service`.
- [ ] **Profile Check Logic**: In `KafkaConsumerService`, implement logic to call `profile-service`'s `GET /api/v1/profiles/{userId}` endpoint for each incoming event.
- [ ] **Profile Creation Logic**: If the profile check returns a 404 Not Found, call the `POST /api/v1/profiles` endpoint to create a new user profile.

#### 4.1.4. End-to-End Testing
- [ ] **Final System Validation**:
    - [ ] Launch the complete system.
    - [ ] Monitor logs to confirm `ingestion-service` is correctly calling `profile-service`.
    - [ ] Query the `user_profiles` table in PostgreSQL to verify that new user profiles are created as expected.

## 5. Phase v0.2: Real-time Processing & Infrastructure Hardening

This phase evolves PulseHub from a simple data ingestion system into a real-time data processing middleware, laying the groundwork for intelligent data activation.

### 5.1. v0.2 Goals & Success Criteria
- **Real-time Insights**: The system must be able to process raw event streams in real-time to compute and update user attributes (e.g., Last Active Time, Page View Count).
- **High-Performance Caching**: A caching layer must be implemented in the `profile-service` to provide low-latency access to user profile data.
- **Architectural Clarity**: The system architecture will be refactored into clear "hot" (real-time) and "cold" (archival) data paths.

### 5.2. v0.2 Architecture Diagram

```mermaid
graph LR
    %% === Kafka 中枢 ===
    Kafka["🧭 Kafka"]
    style Kafka fill:#ddeeff,stroke:#3366cc,stroke-width:2px

    %% === 缓存与数据库 ===
    Redis["🧠 Redis<br/>对象缓存 / 排行榜 / Feed流"]
    PostgreSQL["🗄️ PostgreSQL<br/>用户主数据 / 归档存储"]
    style Redis fill:#e0ffe0,stroke:#339933,stroke-width:2px
    style PostgreSQL fill:#e6e6ff,stroke:#6666cc,stroke-width:2px

    %% === 应用服务 ===
    StreamProcessor["🧪 stream-processor"]
    ProfileService["👤 profile-service"]
    IngestionService["📥 ingestion-service"]
    EventProducer["🧨 event-producer"]

    style StreamProcessor fill:#f0f8ff,stroke:#339,stroke-width:1.5px
    style ProfileService fill:#f0f8ff,stroke:#339,stroke-width:1.5px
    style IngestionService fill:#f0f8ff,stroke:#339,stroke-width:1.5px
    style EventProducer fill:#f0f8ff,stroke:#339,stroke-width:1.5px

    %% === 数据流动链 ===
    EventProducer -- "🔄 Raw Events" --> Kafka
    Kafka -- "Raw Events" --> StreamProcessor
    StreamProcessor -- "Writes State" --> Redis
    StreamProcessor -- "Enriched Events" --> Kafka
    Kafka -- "Enriched Events" --> ProfileService

    ProfileService -- "Cache Ops" --> Redis
    ProfileService -- "R/W Master Data" --> PostgreSQL

    Kafka -- "Raw Events<br/>(for archival)" --> IngestionService
    IngestionService -- "Archive to DB" --> PostgreSQL

```

### 5.3. v0.2 Implementation Plan & Tasks

This implementation plan is derived directly from our `task-master` project management tool and represents the single source of truth for development work in this phase.

- [ ] **Task 7: Set up Redis Caching Layer**
  - **Description**: Integrate Redis as a high-performance, in-memory data store for caching user profiles and supporting real-time operations.
  - **Dependencies**: None
  - **Subtasks**:
    - [ ] **7.1**: Configure Redis connection properties
    - [ ] **7.2**: Implement Redis connection factory and template
    - [ ] **7.3**: Develop cache service abstraction layer
    - [ ] **7.4**: Implement TTL policies and eviction strategies
    - [ ] **7.5**: Implement Redis health checks and monitoring

- [ ] **Task 9: Configure Multi-Topic Kafka Environment**
  - **Description**: Restructure the Kafka environment to handle multiple topics, separating raw events (user-activity-events) from processed results (profile-updates).
  - **Dependencies**: None
  - **Subtasks**:
    - [ ] **9.1**: Define and create Kafka topics
    - [ ] **9.2**: Design partitioning strategy
    - [ ] **9.3**: Configure replication and durability settings
    - [ ] **9.4**: Implement error handling and retry mechanisms
    - [ ] **9.5**: Set up monitoring and alerting
    - [ ] **9.6**: Integrate Kafka with Spring Boot application

- [ ] **Task 10: Create User Profile Model**
  - **Description**: Design and implement the User Profile data model to store enriched user attributes including lastActiveAt timestamp, page view counter, and device classification.
  - **Dependencies**: None
  - **Subtasks**:
    - [ ] **10.1**: Define User Profile Core Attributes
    - [ ] **10.2**: Implement Serialization/Deserialization Methods
    - [ ] **10.3**: Develop Helper Methods and Utility Functions

- [ ] **Task 11: Implement User Profile Service**
  - **Description**: Create a service to manage user profiles, including methods to retrieve, update, and cache user profile data.
  - **Dependencies**: Task 7, Task 10
  - **Subtasks**:
    - [ ] **11.1**: Define User Profile Service Interface
    - [ ] **11.2**: Implement Redis Integration for Profile Caching
    - [ ] **11.3**: Implement Database Fallback Mechanism
    - [ ] **11.4**: Develop Exception Handling Framework
    - [ ] **11.5**: Implement Cache Update Strategies

- [ ] **Task 12: Develop Real-time Event Processor**
  - **Description**: Implement a Kafka consumer service that processes incoming UserActivityEvents in real-time to update user profiles with enriched data.
  - **Dependencies**: Task 9, Task 11
  - **Subtasks**:
    - [ ] **12.1**: Configure Kafka Consumer
    - [ ] **12.2**: Implement Core Event Processing Logic
    - [ ] **12.3**: Develop User Profile Update Mechanism
    - [ ] **12.4**: Implement Comprehensive Error Handling
    - [ ] **12.5**: Set Up Monitoring and Alerting
    - [ ] **12.6**: Optimize Performance and Scalability

- [ ] **Task 13: Implement Profile REST API**
  - **Description**: Create a REST API to expose user profile data with low latency, leveraging the Redis cache for performance.
  - **Dependencies**: Task 11
  - **Subtasks**:
    - [ ] **13.1**: Implement Profile Controller
    - [ ] **13.2**: Implement Input Validation
    - [ ] **13.3**: Implement Error Handling
    - [ ] **13.4**: Create API Documentation

- [ ] **Task 14: Implement Profile Database Repository**
  - **Description**: Create a repository layer to persist user profiles in PostgreSQL as part of the cold path for long-term storage and analytics.
  - **Dependencies**: Task 10
  - **Subtasks**:
    - [ ] **14.1**: Define Entity Classes
    - [ ] **14.2**: Create Repository Interfaces
    - [ ] **14.3**: Implement Database Migration Scripts
    - [ ] **14.4**: Implement Custom Query Methods

- [ ] **Task 15: Implement Cold Path Persistence Service**
  - **Description**: Create a service to persist raw event data to PostgreSQL for long-term storage and future analytics as part of the cold path architecture.
  - **Dependencies**: Task 9
  - **Subtasks**:
    - [ ] **15.1**: Implement Kafka Consumer Setup
    - [ ] **15.2**: Develop Batch Processing Logic
    - [ ] **15.3**: Create Entity Transformation Layer
    - [ ] **15.4**: Implement Error Handling and Recovery
    - [ ] **15.5**: Set Up Monitoring and Metrics

- [ ] **Task 17: Implement Device Type Classification Service**
  - **Description**: Create a service to classify device types from user agent strings to enrich user profiles with device information.
  - **Dependencies**: None
  - **Subtasks**:
    - [ ] **17.1**: Integrate User Agent Parsing Library
    - [ ] **17.2**: Implement Device Classification Logic
    - [ ] **17.3**: Implement Caching Mechanism
    - [ ] **17.4**: Develop Fallback Classification Strategies

- [ ] **Task 18: Implement Monitoring and Alerting**
  - **Description**: Set up comprehensive monitoring and alerting for all components of the system, focusing on the new real-time processing capabilities.
  - **Dependencies**: Task 7, Task 9, Task 11, Task 12
  - **Subtasks**:
    - [ ] **18.1**: Prometheus Integration Setup
    - [ ] **18.2**: Grafana Dashboard Implementation
    - [ ] **18.3**: Alert Configuration and Notification Channels
    - [ ] **18.4**: Custom Application Metrics Implementation
    - [ ] **18.5**: Health Check System Implementation
    - [ ] **18.6**: Log Aggregation and Analysis Setup

- [ ] **Task 19: Implement Hot/Cold Path Integration**
  - **Description**: Create the integration between the hot path (real-time processing) and cold path (archival storage) to ensure data consistency and completeness.
  - **Dependencies**: Task 12, Task 14, Task 15
  - **Subtasks**:
    - [ ] **19.1**: Design and implement reconciliation service
    - [ ] **19.2**: Develop data backfill mechanism
    - [ ] **19.3**: Implement discrepancy monitoring and alerting
    - [ ] **19.4**: Design and implement recovery process
    - [ ] **19.5**: Implement comprehensive audit logging
    - [ ] **19.6**: Develop testing framework and validation suite

- [ ] **Task 20: Create System Documentation**
  - **Description**: Develop comprehensive documentation for the new system architecture, including the hot/cold path design, configuration management, and real-time processing capabilities.
  - **Dependencies**: Task 7, Task 9, Task 10, Task 11, Task 12, Task 13, Task 14, Task 15, Task 17, Task 18, Task 19
  - **Subtasks**:
    - [ ] **20.1**: Create Architecture Diagrams
    - [ ] **20.2**: Develop Configuration Documentation
    - [ ] **20.3**: Write API Documentation
    - [ ] **20.4**: Develop Operational Runbooks
    - [ ] **20.5**: Create Troubleshooting Guides

- [ ] **Task 21: Implement End-to-End Testing Suite**
  - **Description**: Create a comprehensive end-to-end testing suite to validate the entire system flow from event ingestion to profile enrichment and data persistence.
  - **Dependencies**: Task 12, Task 13, Task 15, Task 19
  - **Subtasks**:
    - [ ] **21.1**: Set up test environment
    - [ ] **21.2**: Develop test data generation framework
    - [ ] **21.3**: Implement core test scenarios
    - [ ] **21.4**: Develop assertion and validation framework
    - [ ] **21.5**: Implement performance testing suite
    - [ ] **21.6**: Develop chaos testing capabilities

---
**Note:** This SOW is a living document. Please update the checkboxes as tasks are completed to reflect the real-time progress of the project. 