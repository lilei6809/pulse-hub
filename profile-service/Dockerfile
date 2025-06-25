# Stage 1: Build the application with Maven
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy the entire project context
COPY . .

# Build the specific module and all its reactor dependencies (-am)
# The --mount flag uses Docker's build cache to persist the .m2 directory,
# drastically speeding up dependency downloads in subsequent builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean install -pl profile-service -am -DskipTests

# Stage 2: Create the final, minimal image from an OpenJDK base
FROM openjdk:21-slim

WORKDIR /app

# Copy the executable JAR from the builder stage
COPY --from=builder /app/profile-service/target/*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]