# hms-web-demo 运行镜像
#
# 构建前先在宿主机打包（容器里不再跑 Maven，避免额外拉取构建镜像）：
#   mvn -f demo-app/pom.xml clean package -DskipTests
#   docker build -f demo-app/deploy/Dockerfile -t hms-demo:latest demo-app
#
# 项目要求 Java 25（class file 69），运行时用 25-jre，不要降级到 17。
FROM eclipse-temurin:25-jre

WORKDIR /app

# 只拷贝 fat jar：源码与 target/classes 都不需要进镜像
COPY target/hms-web-demo-0.1.0-SNAPSHOT.jar /app/app.jar

# 沙箱根目录在 compose 里通过 HMS_SANDBOX_ROOT 指向挂载卷
RUN mkdir -p /data/sandbox

EXPOSE 8088

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
