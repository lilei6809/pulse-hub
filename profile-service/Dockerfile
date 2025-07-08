# 使用 OpenJDK 21 作为基础镜像
FROM openjdk:21-jdk-slim

# 设置工作目录
WORKDIR /app

# 复制编译好的 JAR 文件
COPY profile-service/target/profile-service-*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]