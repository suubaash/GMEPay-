# syntax=docker/dockerfile:1.6
# GMEPay+ service image: revenue-ledger
# Multi-stage build: Gradle bootJar on JDK 21, runtime on JRE 21.
# Build context = repo root (so the Gradle multi-module project is visible).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Build the bootJar for this module only, then copy the executable jar
# (not the "-plain" companion produced by the java plugin) to a stable name.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :services:revenue-ledger:bootJar --no-daemon \
 && set -eu \
 && JAR="$(ls services/revenue-ledger/build/libs/ | grep -E '^revenue-ledger-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "services/revenue-ledger/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
