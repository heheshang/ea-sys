# ea-sys-notify 通知服务镜像：多模块 maven 构建 → JRE 运行 fat jar。
# 构建上下文必须为仓库根（父 pom ea-sys-parent 在根）。
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
# 根 pom 聚合全部模块，reactor 解析需所有子模块 pom 存在；构建链只编译 notify + parent。
COPY ea-sys-common/pom.xml ea-sys-common/pom.xml
COPY ea-sys-engine/pom.xml ea-sys-engine/pom.xml
COPY ea-sys-agent/pom.xml ea-sys-agent/pom.xml
COPY ea-sys-channel/pom.xml ea-sys-channel/pom.xml
COPY ea-sys-api/pom.xml ea-sys-api/pom.xml
COPY ea-sys-notify/pom.xml ea-sys-notify/pom.xml
RUN mvn -B -q dependency:go-offline -pl ea-sys-notify -am || true
COPY ea-sys-notify ea-sys-notify
RUN mvn -B -pl ea-sys-notify -am -Dapi.version=1.44 -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/ea-sys-notify/target/ea-sys-notify-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]