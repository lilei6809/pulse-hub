# Use an official OpenJDK runtime as a parent image, Alpine is a lightweight Linux distribution
FROM eclipse-temurin:21-jre-alpine

# Set the working directory in the container
WORKDIR /app

# Copy the fat jar into the container at /app
COPY target/event-producer-0.1.0-SNAPSHOT.jar app.jar

# Make port 8082 available to the world outside this container
EXPOSE 8082

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"] 