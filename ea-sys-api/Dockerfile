# ea-sys-api 后端镜像：多模块 maven 构建 → JRE 运行 fat jar。
# 构建上下文必须为仓库根（依赖 ea-sys-common/engine/agent/channel 模块源码）。
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY ea-sys-common/pom.xml ea-sys-common/pom.xml
COPY ea-sys-engine/pom.xml ea-sys-engine/pom.xml
COPY ea-sys-agent/pom.xml ea-sys-agent/pom.xml
COPY ea-sys-channel/pom.xml ea-sys-channel/pom.xml
COPY ea-sys-api/pom.xml ea-sys-api/pom.xml
COPY ea-sys-notify/pom.xml ea-sys-notify/pom.xml
RUN mvn -B -q dependency:go-offline -pl ea-sys-api -am || true
COPY ea-sys-common ea-sys-common
COPY ea-sys-engine ea-sys-engine
COPY ea-sys-agent ea-sys-agent
COPY ea-sys-channel ea-sys-channel
COPY ea-sys-api ea-sys-api
RUN mvn -B -pl ea-sys-api -am -Dapi.version=1.44 -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/ea-sys-api/target/ea-sys-api-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]