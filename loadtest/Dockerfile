# syntax=docker/dockerfile:1
# Build context is the repo root (for the Maven wrapper): docker build -f loadtest/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw ./
COPY loadtest/pom.xml loadtest/pom.xml
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -f loadtest/pom.xml dependency:go-offline
COPY loadtest/src loadtest/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -f loadtest/pom.xml package

FROM eclipse-temurin:25-jre
COPY --from=build /src/loadtest/target/burst.jar /burst.jar
ENTRYPOINT ["java", "-jar", "/burst.jar"]
