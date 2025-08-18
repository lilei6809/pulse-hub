# 使用 OpenJDK 21 运行时镜像
FROM openjdk:21-jdk-slim

# 设置工作目录
WORKDIR /app

# 复制应用JAR文件
COPY data-sync-service/target/data-sync-service-*.jar app.jar

# 暴露端口
EXPOSE 8087

# 设置JVM时区为UTC
ENV TZ=UTC

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]