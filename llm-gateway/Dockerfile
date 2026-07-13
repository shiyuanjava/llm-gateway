# ---- 构建:Maven + JDK 21(依赖层与源码层分开 COPY,提升重建缓存命中)----
# 国内网络受限时:在 RUN mvn 前加一层写入阿里云 mirror 的 settings.xml,例如
#   RUN mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- 解层:Spring Boot layered jar 拆成变更频率不同的四层 ----
FROM eclipse-temurin:21-jre AS extract
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- 运行:JRE + 非 root 用户;curl 供 compose healthcheck 使用 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r gateway && useradd -r -g gateway gateway \
    && mkdir -p /app/logs && chown -R gateway:gateway /app
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./
USER gateway
EXPOSE 8080 9090
# exec 形式:SIGTERM 直达 JVM,graceful shutdown 生效
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
