# PulseHub Project - Handover & State Summary

**Document Purpose:** This document serves as a comprehensive handover note, providing a snapshot of the project's status, architecture, and key learnings as of the completion of v0.1. It is intended for any developer (including the AI assistant) continuing work on this project.

**Last Updated:** End of Day, v0.1 Completion.

---

## 1. Current Project Status

-   **Milestone Reached:** **v0.1 MVP is 100% complete.**
-   **Last Actions:**
    1.  The entire infrastructure was successfully launched via `docker-compose up`.
    2.  End-to-end data flow was verified: events produced, sent to Kafka, consumed, and persisted to PostgreSQL.
    3.  `README.md` was updated with comprehensive setup and run instructions.
    4.  `SOW.md` was updated to mark all v0.1 tasks as complete.
-   **Next Immediate Task:** Begin work on **Ticket #8: Implement User Profile Service (`profile-service`)**.

---

## 2. Core Project Rules & Conventions

1.  **Environment Variables (`.env` vs `env-config.txt`):**
    -   The AI assistant does not have permission to read `.env` files.
    -   A file named `env-config.txt` is used as a readable substitute.
    -   **Rule:** The user is responsible for keeping `.env` and `env-config.txt` synchronized. When environment variables are needed, the AI should read from `env-config.txt`.

2.  **Source of Truth for Tasks:**
    -   The `SOW.md` (Statement of Work) file in the root directory is the definitive source for all project phases, goals, and implementation tasks. All development work should align with this document.

3.  **Running the Project:**
    -   The main `README.md` contains canonical, up-to-date instructions on how to build, run, verify, and stop the project.

---

## 3. Architecture & Module Breakdown (v0.1)

The project is a **multi-module Maven project** with an **event-driven architecture**.

### 3.1. Parent POM (`pom.xml`)

-   **Purpose:** Acts as the central controller for the entire project. It defines the versions for Java (21), Spring Boot, and other core dependencies in `<dependencyManagement>`. It also configures project-wide Maven plugins, most notably the `spring-boot-maven-plugin` required to build executable JARs.
-   **Modules Included:** `common`, `event-producer`, `ingestion-service`.

### 3.2. `common` Module

-   **Purpose:** A shared library for code used by multiple services. Its primary role is to house the **data contract** for our Kafka events.
-   **Key Technology:** **Protocol Buffers (Protobuf)**.
-   **Key Files:**
    -   `src/main/proto/user_activity_event.proto`: The `.proto` file that defines the structure of our `UserActivityEvent`.
    -   `pom.xml`: Contains the `protobuf-maven-plugin` which automatically compiles the `.proto` file into Java classes during the Maven build process.

### 3.3. `event-producer` Service

-   **Purpose:** A standalone Spring Boot application that simulates a client generating and sending user activity data.
-   **Functionality:** It uses a `@Scheduled` task to periodically create `UserActivityEvent` objects, serialize them using the Protobuf serializer, and send them to the `user-activity-events` Kafka topic.
-   **Key Files:**
    -   `EventProducerApplication.java`: Main application class with the scheduled task.
    -   `KafkaProducerService.java`: Contains the logic for sending messages to Kafka.
    -   `application.yml`: Configures the Kafka broker address and the Protobuf serializer.
    -   `Dockerfile`: Defines how to build the service's Docker image.

### 3.4. `ingestion-service` Service

-   **Purpose:** A standalone Spring Boot application that acts as the entry point for data into our platform.
-   **Functionality:** It listens to the `user-activity-events` Kafka topic. When a message arrives, it deserializes the Protobuf message into a Java object, converts it into a JPA entity (`TrackedEvent`), and saves it to the PostgreSQL database.
-   **Key Files:**
    -   `KafkaConsumerService.java`: Contains the `@KafkaListener` which triggers the consumption logic.
    -   `TrackedEvent.java`: The JPA `@Entity` class that maps to the `tracked_events` database table.
    -   `TrackedEventRepository.java`: The Spring Data JPA repository for database operations.
    -   `application.yml`: Configures the Kafka broker address, consumer group, Protobuf deserializer, and the PostgreSQL database connection.
    -   `Dockerfile`: Defines how to build the service's Docker image.

### 3.5. Infrastructure (`docker-compose.yml`)

-   **Purpose:** Defines and orchestrates the entire backend infrastructure in a single, runnable configuration.
-   **Services Defined:**
    -   **Core Platform:** `event-producer`, `ingestion-service`.
    -   **Messaging:** `kafka`, `schema-registry`, `zookeeper`.
    -   **Database:** `postgres`.
    -   **Tooling:** `kafka-ui`.
-   **Key Configuration:** Uses Docker service names for inter-service communication (e.g., `kafka:9092`) and `depends_on` with health checks to manage startup dependencies.

---

## 4. Critical Lessons from MVP Development

*This section is vital to avoid repeating past mistakes.*

1.  **The "No Main Manifest Attribute" Error:** This fatal error occurs if the `spring-boot-maven-plugin` is not configured to execute its `repackage` goal. Simply declaring it in `<pluginManagement>` is not enough. An `<executions>` block is **mandatory** in the parent `pom.xml` to ensure the final JARs are created as executable Spring Boot applications.

2.  **Silent Spring Component Failure:** If a Spring component (e.g., a `@KafkaListener`) uses a property placeholder (e.g., `topic = "${pulsehub.kafka.topic}"`) and that property is **not defined** in `application.yml`, the component may fail to initialize **silently**, without throwing an error at startup. The application will run, but the component will not work. Always double-check property definitions.

3.  **Docker Networking is Not `localhost`:** Services inside a Docker Compose network must communicate using their defined service names (e.g., `postgres`, `kafka`), not `localhost`.

4.  **Startup Order Matters:** Services that depend on others (e.g., any service depending on `schema-registry` or `kafka`) must use `depends_on` with `condition: service_healthy` in `docker-compose.yml` to prevent connection errors during startup races.

5.  **POM Dependency Hygiene:** A `ClassNotFoundException` at runtime almost always means a missing dependency in the correct module's `pom.xml`. For example, `ingestion-service` required `kafka-protobuf-serializer` to be explicitly added, even though it was already in the parent POM's dependency management. 