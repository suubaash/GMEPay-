#!/bin/bash
# One-shot: extract GME Internal Root CA + patch all service/sim Dockerfiles to
# trust it during the Gradle build. Idempotent (skips already-patched files).
set -eu
REPO=/mnt/d/GMEPay+/code
CERT_DIR="$REPO/docker/certs"
mkdir -p "$CERT_DIR"

# --- 1. Extract the self-signed root CA from the live TLS chain ---
TMP=$(mktemp -d)
openssl s_client -connect repo.maven.apache.org:443 -showcerts </dev/null 2>/dev/null > "$TMP/chain.txt"
awk -v d="$TMP" 'BEGIN{n=0} /-----BEGIN CERTIFICATE-----/{n++} {if(n>0) print > (d"/c" n ".pem")}' "$TMP/chain.txt"
ROOT=""
for f in "$TMP"/c*.pem; do
  s=$(openssl x509 -in "$f" -noout -subject 2>/dev/null | sed 's/^subject=//')
  i=$(openssl x509 -in "$f" -noout -issuer  2>/dev/null | sed 's/^issuer=//')
  [ -n "$s" ] && [ "$s" = "$i" ] && ROOT="$f"
done
if [ -z "$ROOT" ]; then echo "ERROR: no self-signed root CA in chain"; exit 1; fi
cp "$ROOT" "$CERT_DIR/gme-root-ca.crt"
echo "== extracted root CA =="
openssl x509 -in "$CERT_DIR/gme-root-ca.crt" -noout -subject -enddate

# --- 2. Patch every Dockerfile build stage to import the CA before Gradle runs ---
patched=0
for df in "$REPO"/services/*/Dockerfile "$REPO"/simulators/*/Dockerfile; do
  if grep -q "gme-root-ca.crt" "$df"; then continue; fi
  awk '
    { sub(/\r$/, "") }
    { print }
    /^COPY \. \.$/ && !done {
      print "# Trust the GME Internal Root CA so Gradle can fetch dependencies through the"
      print "# corporate TLS-inspection firewall (self-signed cert in the chain otherwise"
      print "# fails PKIX validation). Cert ships in the build context via COPY . . above."
      print "RUN keytool -importcert -noprompt -trustcacerts -alias gme-root \\"
      print "    -file docker/certs/gme-root-ca.crt -cacerts -storepass changeit"
      done=1
    }
  ' "$df" > "$df.tmp" && mv "$df.tmp" "$df"
  patched=$((patched+1))
done
echo "== patched $patched Dockerfiles =="
