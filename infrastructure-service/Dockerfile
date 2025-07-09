# 使用 OpenJDK 21 作为基础镜像  
FROM openjdk:21-jdk-slim

# 安装 curl 和 wget 用于健康检查
RUN apt-get update && apt-get install -y curl wget && rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 复制编译好的 JAR 文件
COPY infrastructure-service/target/infrastructure-service-*.jar app.jar

# 暴露端口
EXPOSE 8085

# 设置健康检查 - 更宽松的检查设置
HEALTHCHECK --interval=30s --timeout=15s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8085/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"] 