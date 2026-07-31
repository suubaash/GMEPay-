# syntax=docker/dockerfile:1.6
# GMEPay+ simulator image: sim-nepal-qr (Nepal partner QR simulator — Khalti/Fonepay).
# Stands in for the real Nepal partner endpoint in single-host / sandbox deploys.
# Standalone Gradle build (own settings.gradle) driven via the root wrapper with -p.
# Build context = repo root (so the wrapper + gradle dirs are visible).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
# Trust the GME Internal Root CA so Gradle can fetch dependencies through the
# corporate TLS-inspection firewall (self-signed cert in the chain otherwise
# fails PKIX validation). Cert ships in the build context via COPY . . above.
RUN keytool -importcert -noprompt -trustcacerts -alias gme-root \
    -file docker/certs/gme-root-ca.crt -cacerts -storepass changeit
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew -p simulators/sim-nepal-qr bootJar --no-daemon --no-watch-fs \
 && set -eu \
 && JAR="$(ls simulators/sim-nepal-qr/build/libs/ | grep -E '^sim-nepal-qr-[0-9].*\.jar$' | grep -v -- '-plain\.jar$' | head -n1)" \
 && cp "simulators/sim-nepal-qr/build/libs/${JAR}" /workspace/app.jar

FROM eclipse-temurin:25-jre AS runtime

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

# sim-nepal-qr persists its records/txns as append-only JSONL under a
# WORKDIR-relative path (gme.sim.nepal-qr.data-dir=data/sim-nepal-qr), so that
# directory must exist and be owned by the run user. sim-ninepay and sim-sendmn
# persist nothing, which is why only this simulator gets the extra dir.
RUN mkdir -p /app/data/sim-nepal-qr \
 && chown -R 10001:10001 /app/data

USER 10001:10001

# Runs on 8080 in-container (SERVER_PORT override) so the shared TCP health probe applies.
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
