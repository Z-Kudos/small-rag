# Spring Cloud Gateway 镜像（WebFlux）。构建上下文必须是仓库根目录。
ARG MAVEN_IMAGE=docker.io/library/maven:3.9-eclipse-temurin-25
ARG JRE_IMAGE=docker.io/library/eclipse-temurin:25-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml ./
COPY gateway/pom.xml ./gateway/pom.xml
COPY gateway/src ./gateway/src
COPY docker/maven-settings.xml ./maven-settings.xml
RUN mvn -B -q -s /workspace/maven-settings.xml -f gateway/pom.xml -DskipTests clean package

FROM ${JRE_IMAGE}
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/gateway/target/small-rag-gateway-*.jar /app/app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 18080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/18080' || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
