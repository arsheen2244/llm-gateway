# syntax=docker/dockerfile:1
# Build from the repository root (the module needs the parent POM):
#   docker build -f mock-provider/Dockerfile .

# ---- Stage 1: build the jar with Maven ----
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY mock-provider/pom.xml mock-provider/
COPY gateway/pom.xml gateway/
COPY usage-consumer/pom.xml usage-consumer/
COPY mock-provider/src mock-provider/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl mock-provider package -DskipTests

# ---- Stage 2: runtime image with only a JRE and the jar ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
# The runnable jar carries the "-exec" classifier (see mock-provider/pom.xml).
COPY --from=build /src/mock-provider/target/mock-provider-0.1.0-SNAPSHOT-exec.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
