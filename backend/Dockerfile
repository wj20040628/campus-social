# ============================================================
# 后端 Dockerfile:多阶段构建(Maven 打包 → JRE 运行)
# 构建:docker build -t campus-social-backend -f backend/Dockerfile backend
# 运行参数走 docker-compose,此处默认值仅兜底
# ============================================================

# ---- 构建阶段:编译出可执行 jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# 先下依赖(利用 Docker 层缓存)
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package
# 产物:target/campus-social-1.0.0.jar

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# 上传目录:相对 /app/uploads(容器内,可挂载卷持久化)
RUN mkdir -p /app/uploads
COPY --from=build /app/target/campus-social-1.0.0.jar app.jar

# 启动:生产用 application.yml 的 ${ENV} 占位,变量由 compose 注入
ENV UPLOAD_DIR=/app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
