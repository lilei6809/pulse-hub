# ==== 构建阶段 ====
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

# 可选：加入 Maven settings.xml 加速（阿里云镜像）
# COPY ../../settings.xml /root/.m2/settings.xml

COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    mvn clean install -pl event-producer -am -DskipTests

# ==== 运行阶段 ====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/event-producer/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
