FROM openjdk:17-jdk-slim

LABEL maintainer="PulseHub Team"

# 添加应用JAR包
COPY target/discovery-service-*.jar /app/discovery-service.jar

# 设置工作目录
WORKDIR /app

# 暴露Eureka服务器端口
EXPOSE 8761

# 定义环境变量
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE="docker"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar discovery-service.jar"] 