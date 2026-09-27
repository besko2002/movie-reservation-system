# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: build the Spring Boot fat jar with the project's own Maven wrapper.
# pom.xml + .mvn + mvnw are copied first so the dependency download layer is
# reused as long as the build files do not change.
# ---------------------------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Pre-fetch dependencies and plugins (layer cache). -B = batch mode, no colours.
RUN ./mvnw -B -q -DskipTests dependency:go-offline

COPY src/ src/

RUN ./mvnw -q -DskipTests package \
    # target/ also holds <artifact>.jar.original (the pre-repackage jar); *.jar
    # matches only the executable one.
    && cp target/*.jar /build/app.jar

# ---------------------------------------------------------------------------
# Stage 2: runtime. JRE only (no compiler, no Maven, no sources) and a
# non-root user. The Ubuntu-based Temurin JRE image already ships curl, which
# HEALTHCHECK below uses; the `command -v curl` guard fails the build loudly if
# a future base image ever drops it.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

RUN command -v curl >/dev/null || (echo "curl missing from base image" >&2; exit 1)

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid 1001 --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app \
    && chown app:app /app

WORKDIR /app

COPY --from=build --chown=app:app /build/app.jar /app/app.jar

USER app

EXPOSE 8080

# Flyway migrations run at startup, so allow a generous start period.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar"]
