# syntax=docker/dockerfile:1.6
# GMEPay+ simulator image: sim-sendmn (SendMN Mongolia QR scheme simulator).
# Stands in for the real SendMN (QPay-fronted) endpoint in single-host / sandbox deploys.
# Standalone Gradle build (own settings.gradle) driven via the root wrapper with -p.
# Build context = repo root (so the wrapper + gradle dirs are visible).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Trust the GME Internal Root CA so Gradle can fetch dependencies through the
# corporate TLS-inspection firewall (self-signed cert in the chain otherwise
# fails PKIX validation). Cert ships in the build context via COPY . . above.
RUN keytool -importcert -noprompt -trustcacerts -alias gme-root \
    -file docker/certs/gme-root-ca.crt -cacerts -storepass changeit
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew -p simulators/sim-sendmn bootJar --no-daemon --no-watch-fs \
 && set -eu \
 && JAR="$(ls simulators/sim-sendmn/build/libs/ | grep -E '^sim-sendmn-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "simulators/sim-sendmn/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
# Runs on 8080 in-container (SERVER_PORT override) so the shared TCP health probe applies.
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
