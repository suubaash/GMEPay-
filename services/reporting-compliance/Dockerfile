# syntax=docker/dockerfile:1.6
# GMEPay+ service image: reporting-compliance
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
# Build the bootJar for this module only, then copy the executable jar
# (not the "-plain" companion produced by the java plugin) to a stable name.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew :services:reporting-compliance:bootJar --no-daemon --no-watch-fs \
 && set -eu \
 && JAR="$(ls services/reporting-compliance/build/libs/ | grep -E '^reporting-compliance-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "services/reporting-compliance/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime

# ---------------------------------------------------------------------------
# Run unprivileged — gap T5-5 (CISO audit 2026-07-28 §14 found 23 of 24 images
# running as root). eclipse-temurin ships no non-root account, so create one.
# UID/GID 10001 is deliberately > 10000 so it cannot collide with a host system
# account, and it matches global.containerSecurityContext.runAsUser in
# deploy/helm/gmepay/values.yaml. `USER` is given NUMERICALLY on purpose: the
# kubelet can only pre-verify runAsNonRoot when the image's user is a UID, not a
# name it would have to resolve inside the image.
# ---------------------------------------------------------------------------
RUN groupadd --system --gid 10001 gmepay \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin gmepay

WORKDIR /app
# The jar stays root-OWNED and only group-readable: the process can read its own
# code but cannot rewrite it. Nothing else under /app is writable, which is what
# makes readOnlyRootFilesystem viable in the chart — the JVM's only writable need
# is java.io.tmpdir=/tmp, mounted there as an emptyDir.
COPY --from=build --chown=root:10001 --chmod=0640 /workspace/app.jar /app/app.jar

# reporting-compliance is the one service image that writes UNDER /app by
# default: gmepay.reporting.bok.outbound-dir=./build/bok-out and
# gmepay.reporting.kofiu.output-dir=./build/kofiu-out are WORKDIR-relative.
# Pre-create them owned by the run user; the chart additionally mounts an
# emptyDir at /app/build so they survive readOnlyRootFilesystem.
RUN mkdir -p /app/build/bok-out /app/build/kofiu-out \
 && chown -R 10001:10001 /app/build

USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
