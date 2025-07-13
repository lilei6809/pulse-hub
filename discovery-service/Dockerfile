FROM openjdk:21-jdk-slim

# 设置工作目录
WORKDIR /app

# 添加应用JAR包
COPY discovery-service/target/discovery-service-*.jar app.jar


# 暴露Eureka服务器端口
EXPOSE 8761

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]