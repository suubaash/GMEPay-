# syntax=docker/dockerfile:1.6
# GMEPay+ service image: scheme-adapter-sendmn
# Multi-stage build: Gradle bootJar on JDK 21, runtime on JRE 21.
# Build context = repo root (so the Gradle multi-module project is visible).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Trust the GME Internal Root CA so Gradle can fetch dependencies through the
# corporate TLS-inspection firewall (self-signed cert in the chain otherwise
# fails PKIX validation). Cert ships in the build context via COPY . . above.
RUN keytool -importcert -noprompt -trustcacerts -alias gme-root \
    -file docker/certs/gme-root-ca.crt -cacerts -storepass changeit
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :services:scheme-adapter-sendmn:bootJar --no-daemon --no-watch-fs \
 && set -eu \
 && JAR="$(ls services/scheme-adapter-sendmn/build/libs/ | grep -E '^scheme-adapter-sendmn-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "services/scheme-adapter-sendmn/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8093
ENTRYPOINT ["java","-jar","/app/app.jar"]
