# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom，利用层缓存避免每次改代码都重新拉依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre

# 时区与编码：应用内部统一 UTC，容器时区固定 UTC 避免日志/DB 时间偏移
ENV TZ=UTC \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r ato && useradd -r -g ato -d /app -s /sbin/nologin ato

WORKDIR /app
COPY --from=builder /build/target/ato-account-*.jar /app/app.jar
RUN chown -R ato:ato /app

USER ato
EXPOSE 8080

# 健康检查：/api/v1/auth/login 匿名可达，未带参数返回 HTTP 200 + 业务错误码，足以判定进程存活
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1:8080/api/v1/auth/login -X POST || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
