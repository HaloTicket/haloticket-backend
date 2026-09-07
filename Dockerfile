# syntax=docker/dockerfile:1
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-21-alpine
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${BUILD_IMAGE} AS build
ARG SERVICE
WORKDIR /build
COPY ${SERVICE}/pom.xml ./pom.xml
# Maven dependencies stay in BuildKit's cache, never in the runtime image.
RUN --mount=type=cache,id=haloticket-maven,target=/root/.m2,sharing=locked \
    mvn -B -ntp dependency:go-offline -DskipTests
COPY ${SERVICE}/src ./src
RUN --mount=type=cache,id=haloticket-maven,target=/root/.m2,sharing=locked \
    mvn -B -ntp package -DskipTests && \
    cp target/*.jar application.jar && \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./
USER app
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=15 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "application.jar"]
