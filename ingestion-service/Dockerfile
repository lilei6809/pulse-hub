# Use an official OpenJDK runtime as a parent image, Alpine is a lightweight Linux distribution
FROM eclipse-temurin:21-jre-alpine

# Set the working directory in the container
WORKDIR /app

# Copy the fat jar into the container at /app
# The jar is built by the spring-boot-maven-plugin
COPY target/ingestion-service-0.1.0-SNAPSHOT.jar app.jar

# Make port 8081 available to the world outside this container
EXPOSE 8081

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"] 