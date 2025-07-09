# PulseHub - Technology Stack & Choices

*This document outlines the official technology stack for the PulseHub project. It serves as a single source of truth for versions, components, and the rationale behind our choices.*

## 1. Guiding Principles

- **Modern & Performant:** We will prioritize modern, stable versions of technologies to leverage new features, performance improvements, and security patches.
- **Scalability-First:** All choices are made with future scalability in mind.
- **Right Tool for the Job:** We will introduce new components (e.g., different databases, caching layers) as the complexity of our use cases demands them.

## 2. Core Stack (MVP v0.2)

This is the foundational technology stack we will use to build the initial version of PulseHub.

| Category | Technology | Version / Configuration | Rationale |
| :--- | :--- | :--- | :--- |
| **Language/Framework** | **Java & Spring Boot** | **Java 21 (LTS)**, **Spring Boot 3.x** | **Java 21's Virtual Threads (Project Loom)** are key for building highly-concurrent, I/O-bound services like ours with simple, readable code. Spring Boot 3 provides robust support for Java 21 and a mature microservices ecosystem. |
| **Messaging Bus** | **Apache Kafka** | Latest Stable Release | The de-facto standard for high-throughput, distributed event streaming. We will run it in **KRaft mode**, eliminating the Zookeeper dependency for a simpler, more modern architecture. |
| **Database** | **PostgreSQL** | Latest Stable Release | A powerful, reliable open-source RDBMS. Its first-class support for `JSONB` is ideal for storing events with flexible, dynamic properties. |
| **Build & Dependencies** | **Maven** | 3.9+ | The standard for managing dependencies and build lifecycles in a multi-module Java project. |
| **Containerization** | **Docker & Docker Compose** | Latest Stable Releases | Ensures a consistent, reproducible development environment and simplifies local orchestration of our multi-service architecture. |
|  | MongoDB |  |  |
|  | Redis |  |  |
|  | Kafka Stream |  |  |

## 3. Future & Ecosystem Stack

This section lists technologies that are anticipated to be integrated as PulseHub evolves. The MVP will be designed to be compatible with these future additions.

| Category | Technology | Potential Role in PulseHub |
| :--- | :--- | :--- |
| **Cloud Native** | **Spring Cloud** | For service discovery (Eureka/Consul), API gateway (Spring Cloud Gateway), and distributed configuration (Config Server) as we add more microservices. |
| **Caching / In-Memory**| **Redis** | For high-performance caching of user profiles, distributed locking, or implementing rate limiters. |
| **NoSQL Database** | **MongoDB** | A potential candidate for storing complex, nested user profile documents, where a flexible schema is highly beneficial. |
| **Search & Analytics** | **Elasticsearch** | For advanced search capabilities on user data and for building powerful analytics/observability dashboards (ELK Stack). |
| **Alternate Messaging**| **RabbitMQ** | May be used for specific use cases requiring complex routing, RPC-style communication, or task queues that differ from Kafka's streaming model. |

---
*This document should be updated if any new technology is introduced or a strategic change is made.* 