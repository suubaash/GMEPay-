# syntax=docker/dockerfile:1.6
# GMEPay+ simulator image: sim-nepal-qr (Nepal partner QR simulator — Khalti/Fonepay).
# Stands in for the real Nepal partner endpoint in single-host / sandbox deploys.
# Standalone Gradle build (own settings.gradle) driven via the root wrapper with -p.
# Build context = repo root (so the wrapper + gradle dirs are visible).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew -p simulators/sim-nepal-qr bootJar --no-daemon \
 && set -eu \
 && JAR="$(ls simulators/sim-nepal-qr/build/libs/ | grep -E '^sim-nepal-qr-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "simulators/sim-nepal-qr/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
# Runs on 8080 in-container (SERVER_PORT override) so the shared TCP health probe applies.
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
