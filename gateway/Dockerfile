# syntax=docker/dockerfile:1
# Build from the repository root (the module needs the parent POM):
#   docker build -f gateway/Dockerfile .

# ---- Stage 1: build the jar with Maven ----
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY mock-provider/pom.xml mock-provider/
COPY gateway/pom.xml gateway/
COPY mock-provider/src mock-provider/src
COPY gateway/src gateway/src
# The cache mount keeps downloaded dependencies between builds.
# Tests run in CI, not during image builds. -am also builds mock-provider,
# which the gateway's (skipped) tests depend on.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl gateway -am package -DskipTests

# ---- Stage 2: runtime image with only a JRE and the jar ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /src/gateway/target/gateway-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
# Size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
