# Stage 1: Build the application with Maven
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy the entire project context
COPY . .

# Build the specific module and all its reactor dependencies (-am)
# This is a robust way to ensure modules are built in the correct order.
# The --mount flag uses Docker's build cache to persist the .m2 directory,
# drastically speeding up dependency downloads in subsequent builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean install -pl ingestion-service -am -DskipTests

# Stage 2: Create the final, minimal image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the executable JAR from the builder stage
COPY --from=builder /app/ingestion-service/target/*.jar app.jar

# Make port 8081 available to the world outside this container
EXPOSE 8081

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"] 