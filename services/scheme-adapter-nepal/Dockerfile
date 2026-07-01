# syntax=docker/dockerfile:1.6
# GMEPay+ service image: scheme-adapter-nepal
# Multi-stage build: Gradle bootJar on JDK 21, runtime on JRE 21.
# Build context = repo root (so the Gradle multi-module project is visible).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :services:scheme-adapter-nepal:bootJar --no-daemon \
 && set -eu \
 && JAR="$(ls services/scheme-adapter-nepal/build/libs/ | grep -E '^scheme-adapter-nepal-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "services/scheme-adapter-nepal/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8092
ENTRYPOINT ["java","-jar","/app/app.jar"]
