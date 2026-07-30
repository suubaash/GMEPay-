#!/usr/bin/env python3
"""Helm chart wiring guard (gap register T3-9, T3-10) + batch-ops wiring (T3-4).

T3-10 was recorded as a single fact: ``payment-executor`` shipped with no datasource in the chart,
so a Helm deploy silently ran the money path on in-memory H2.  The register's own note said what
mattered about it -- "it implies the Helm path has never been exercised end-to-end" -- so this
script is the systematic version of that one finding.  It found four more datasource omissions, two
services missing from the chart entirely, two Secret keys referenced but never declared, and a
chart-wide DNS defect.  All are asserted below.

The requirements are DERIVED FROM THE CODE, never hardcoded:

  * a service is DEPLOYABLE if ``services/<name>/Dockerfile`` exists -> it must have a chart entry;
  * a service NEEDS A DATASOURCE if its shipped ``application.*`` resolves ``spring.datasource.url``
    from an env var with an in-memory H2 fallback -> the chart must set ``SPRING_DATASOURCE_URL``
    (and the credential keys), because the fallback is silent data loss, not a startup error;
  * every ``${ENV_VAR}`` placeholder with NO default is MANDATORY -> something must supply it;
  * every ``${ENV_VAR:default}`` whose default points at an in-cluster hostname or localhost is a
    DEV DEFAULT -> the chart must override it, because inheriting it into Kubernetes is either a
    wrong port or a connection to nothing;
  * every ``envSecretKeys`` / ``envSecretAliases`` entry must exist in ``secrets.data`` -- a
    secretKeyRef to an absent key makes the kubelet refuse to start the container.

Comment lines are stripped before scanning, so a variable named only in a comment (api-gateway's
documented ``${ACME_API_KEY}`` example) is correctly NOT treated as a requirement.

It also asserts the two structural facts the register needs to stay honest:

  * **T3-10 / DNS.** Services render as ``{{ .Release.Name }}-<key>``, so every in-chart base URL
    must carry ``{{ .Release.Name }}``. A bare ``http://config-registry:8080`` resolves to nothing.
    This requires ``_deployment.tpl`` to pass per-service env through ``tpl``; asserted too.
  * **T3-9.** The chart deploys NO StatefulSet/PVC and no datastore. That is a deliberate
    "datastores are external" posture, not an oversight, so the script asserts it stays *documented*
    rather than asserting StatefulSets exist.

Section 6 covers T3-4: the batch-ops calendar must be wired into both batch services and must ship
EMPTY (populating it is an operator/business input), and the two duplicated ``BusinessCalendar``
copies must agree.

Run (no servers, no Docker, no Helm):
    python scripts/check_helm_chart_wiring.py

Exit 0 = the chart provides what the code requires, 1 = a mismatch (printed with the file).
"""
from __future__ import annotations

import glob
import io
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is required: python -m pip install pyyaml")

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HELM = os.path.join(ROOT, "deploy", "helm", "gmepay")
OVERLAYS = ["values-aws.yaml", "values-azure.yaml", "values-onprem.yaml"]

checks: list[tuple[bool, str, str]] = []
failures: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> bool:
    checks.append((bool(ok), label, detail))
    if not ok:
        failures.append(label + ("" if not detail else " - " + detail))
    return bool(ok)


def read(path: str) -> str:
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text: str) -> str:
    """Drop whole-line comments. Both .properties and .yml use '#'.

    Deliberately conservative: only lines whose first non-space char is '#' are removed, so an
    inline '#' inside a value is preserved. This is what stops a documented example variable
    (api-gateway's ``${ACME_API_KEY}`` in a comment block) from being read as a real requirement.
    """
    return "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))


PLACEHOLDER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(:([^}]*))?\}")
# Hosts that are NOT deployed by this chart (T3-9: datastores are external). A dev default that
# names one of these is the operator's own endpoint to set, not a chart omission, and it is
# supplied through the shared ABI ConfigMap instead of per-service env.
EXTERNAL_HOSTS = {
    "postgres", "redis", "kafka", "mongo", "minio", "keycloak", "schema-registry", "zookeeper",
}


def service_config_text(service: str) -> str:
    files = sorted(
        glob.glob(os.path.join(ROOT, "services", service, "src", "main", "resources", "application*.properties"))
        + glob.glob(os.path.join(ROOT, "services", service, "src", "main", "resources", "application*.yml"))
    )
    return strip_comments("\n".join(read(f) for f in files))


def placeholders(text: str) -> dict[str, str | None]:
    """{VAR: default-or-None}. Spring's own ``${java.io.tmpdir}`` style names are skipped."""
    out: dict[str, str | None] = {}
    for m in PLACEHOLDER.finditer(text):
        var, has_default, default = m.group(1), m.group(2), m.group(3)
        if "." in var:
            continue
        # First occurrence wins; a later bare ${VAR} must not erase a real default.
        if var not in out or (out[var] is None and has_default):
            out[var] = default if has_default else None
    return out


# ---------------------------------------------------------------------------
# 0. Load the chart
# ---------------------------------------------------------------------------
base = yaml.safe_load(read(os.path.join(HELM, "values.yaml"))) or {}
chart_services: dict = base.get("services") or {}
abi_keys = set(base.get("abi") or {})
secret_data = dict((base.get("secrets") or {}).get("data") or {})
secret_keys = set(secret_data)
deployment_tpl = read(os.path.join(HELM, "templates", "_deployment.tpl"))

print("=" * 78)
print("Helm chart wiring guard - T3-10 (chart omissions), T3-9 (external datastores),")
print("                          T3-4 (batch-ops calendar)")
print("=" * 78)

# ---------------------------------------------------------------------------
# 1. Every deployable has a chart entry
# ---------------------------------------------------------------------------
print("\n-- 1. deployables present in the chart ------------------------------------")
deployables = sorted(
    d for d in os.listdir(os.path.join(ROOT, "services"))
    if os.path.isfile(os.path.join(ROOT, "services", d, "Dockerfile"))
)
for svc in deployables:
    check(svc in chart_services,
          f"chart: deployable service '{svc}' has a values.yaml entry",
          "" if svc in chart_services else "has a Dockerfile but the chart never creates it")

for app in sorted(glob.glob(os.path.join(ROOT, "apps", "*", "Dockerfile"))):
    name = os.path.basename(os.path.dirname(app))
    check(name in chart_services, f"chart: deployable app '{name}' has a values.yaml entry")

# ---------------------------------------------------------------------------
# 2. THE T3-10 CHECK: a service with an in-memory H2 fallback must get a real datasource
# ---------------------------------------------------------------------------
print("\n-- 2. datasource wiring (the payment-executor omission, generalised) ------")
H2_MEM = re.compile(r"jdbc:h2:mem:(\w+)")
for svc in deployables:
    text = service_config_text(svc)
    m = H2_MEM.search(text)
    if not m or "spring.datasource.url" not in text.replace("\n", " ").replace("  ", " ") \
            and "url:" not in text:
        continue
    if not m:
        continue
    entry = chart_services.get(svc) or {}
    env = entry.get("env") or {}
    sec = set(entry.get("envSecretKeys") or []) | set((entry.get("envSecretAliases") or {}).values())
    url = str(env.get("SPRING_DATASOURCE_URL", ""))
    check(bool(url),
          f"chart: {svc} sets SPRING_DATASOURCE_URL (else silent in-memory H2 '{m.group(1)}')",
          "" if url else f"MISSING -> would run on jdbc:h2:mem:{m.group(1)} and lose data on restart")
    if url:
        check(url.startswith("jdbc:postgresql://"),
              f"chart: {svc} datasource is a real PostgreSQL URL", url)
    check("SPRING_DATASOURCE_USERNAME" in sec and "SPRING_DATASOURCE_PASSWORD" in sec,
          f"chart: {svc} pulls the datasource credentials from the Secret",
          "" if "SPRING_DATASOURCE_PASSWORD" in sec else "envSecretKeys is missing the credential pair")

# ---------------------------------------------------------------------------
# 3. Mandatory + dev-default env vars are supplied
# ---------------------------------------------------------------------------
print("\n-- 3. mandatory and dev-default env vars ---------------------------------")
DEV_DEFAULT = re.compile(r"https?://([A-Za-z0-9._-]+)(:(\d+))?")
for svc in deployables:
    if svc not in chart_services:
        continue      # already failed in section 1
    entry = chart_services[svc]
    env = entry.get("env") or {}
    provided = set(env) | abi_keys | set(entry.get("envSecretKeys") or []) \
        | set(entry.get("envSecretAliases") or {})
    for var, default in sorted(placeholders(service_config_text(svc)).items()):
        if var in provided:
            continue
        if default is None:
            check(False, f"chart: {svc} is supplied MANDATORY env {var}",
                  "no default in application.* and nothing provides it")
            continue
        m = DEV_DEFAULT.match(default.strip())
        if not m:
            continue                      # a plain scalar default (flag/threshold) is fine
        host = m.group(1)
        if host in EXTERNAL_HOSTS or any(host.startswith(h) for h in EXTERNAL_HOSTS):
            continue                      # T3-9: external datastore, supplied via the ABI ConfigMap
        check(False,
              f"chart: {svc} overrides dev default {var} (image default '{default.strip()}')",
              "localhost/simulator or an unprefixed in-cluster host would not resolve in Kubernetes"
              if "localhost" in host or "127.0.0.1" in host
              else "bare hostname/port: Services are named {{ .Release.Name }}-<svc> on port 8080")

# ---------------------------------------------------------------------------
# 4. Secret references resolve, and the DNS/tpl mechanism is in place
# ---------------------------------------------------------------------------
print("\n-- 4. secret references + release-aware service DNS ----------------------")
for svc, entry in sorted(chart_services.items()):
    for key in (entry.get("envSecretKeys") or []):
        check(key in secret_keys,
              f"chart: {svc} envSecretKeys '{key}' exists in secrets.data",
              "" if key in secret_keys
              else "secretKeyRef to an undeclared key - the kubelet refuses to start the container")
    for env_name, key in (entry.get("envSecretAliases") or {}).items():
        check(key in secret_keys,
              f"chart: {svc} alias {env_name} -> secrets.data '{key}' exists")

check("tpl (printf \"%v\" $v) $root" in deployment_tpl,
      "_deployment.tpl: per-service env is rendered through tpl (lets values use .Release.Name)",
      "without this, a base URL containing {{ .Release.Name }} ships literally")
check('name: {{ include "gmepay.svcName" $ctx }}' in deployment_tpl,
      "_deployment.tpl: the Service is named by gmepay.svcName (release-prefixed)")

svc_names = set(chart_services)
BARE_URL = re.compile(r"http://(" + "|".join(sorted((re.escape(s) for s in svc_names), key=len, reverse=True)) + r"):")
for path in [os.path.join(HELM, "values.yaml")] + [os.path.join(HELM, o) for o in OVERLAYS]:
    hits = [ln.strip() for ln in strip_comments(read(path)).splitlines() if BARE_URL.search(ln)]
    check(not hits,
          f"{os.path.basename(path)}: no bare in-chart hostname (must carry {{{{ .Release.Name }}}})",
          "; ".join(hits[:3]))

# ---------------------------------------------------------------------------
# 5. T3-9 - the external-datastore assumption stays DOCUMENTED, not silently assumed
# ---------------------------------------------------------------------------
print("\n-- 5. T3-9: no StatefulSet/PVC, and that is written down ------------------")
tpl_text = "\n".join(read(p) for p in glob.glob(os.path.join(HELM, "templates", "*")))
check("kind: StatefulSet" not in tpl_text,
      "chart: ships no StatefulSet (datastores are external - T3-9 posture, unchanged)")
check("kind: PersistentVolumeClaim" not in tpl_text,
      "chart: ships no PVC (datastores are external - T3-9 posture, unchanged)")
values_text = read(os.path.join(HELM, "values.yaml"))
check("EXTERNAL" in values_text.upper() and "T3-9" in values_text,
      "values.yaml: states explicitly that datastores are EXTERNAL to this chart (T3-9)",
      "the assumption must be documented where an operator will read it")

# ---------------------------------------------------------------------------
# 6. T3-4 - batch-ops calendar wiring
# ---------------------------------------------------------------------------
print("\n-- 6. T3-4: batch-ops business-day calendar ------------------------------")
CAL_VARS = [
    "GMEPAY_CALENDAR_NON_BUSINESS_DATES",
    "GMEPAY_CALENDAR_NON_BUSINESS_DAYS_OF_WEEK",
    "GMEPAY_CALENDAR_VERIFIED_THROUGH",
    "GMEPAY_CALENDAR_FAIL_CLOSED",
]
BATCH_SERVICES = ["settlement-reconciliation", "scheme-adapter-zeropay"]
for svc in BATCH_SERVICES:
    env = (chart_services.get(svc) or {}).get("env") or {}
    for var in CAL_VARS:
        check(var in env, f"chart: {svc} declares {var}")
    for var in CAL_VARS[:3]:
        if var in env:
            check(str(env[var]) == "",
                  f"chart: {svc} ships {var} EMPTY (populating it is an operator/business input)",
                  f"got {env[var]!r} - the chart must not invent a holiday calendar")
    if "GMEPAY_CALENDAR_FAIL_CLOSED" in env:
        check(str(env["GMEPAY_CALENDAR_FAIL_CLOSED"]).lower() == "false",
              f"chart: {svc} ships fail-closed=false (an empty calendar must not stop every batch)")

# The two BusinessCalendar copies must agree (duplicated because libs/ was owned elsewhere).
cal_paths = [
    os.path.join(ROOT, "services", "settlement-reconciliation", "src", "main", "java", "com", "gme",
                 "pay", "settlement", "calendar", "BusinessCalendar.java"),
    os.path.join(ROOT, "services", "scheme-adapter-zeropay", "src", "main", "java", "com", "gme",
                 "pay", "scheme", "zeropay", "ops", "calendar", "BusinessCalendar.java"),
]
if all(os.path.exists(p) for p in cal_paths):
    bodies = []
    for p in cal_paths:
        body = read(p)
        # Compare the logic, not the package line or the javadoc prose.
        body = re.sub(r"/\*\*.*?\*/", "", body, flags=re.S)
        body = re.sub(r"^package .*?;$", "", body, flags=re.M)
        bodies.append(re.sub(r"\s+", " ", body).strip())
    check(bodies[0] == bodies[1],
          "BusinessCalendar: the settlement and zeropay copies are logically identical",
          "the duplicate has drifted - promote it to libs/ or re-sync")
    for p in cal_paths:
        src = read(p)
        check("@org.springframework.beans.factory.annotation.Autowired" in src
              or "@Autowired" in src,
              f"{os.path.relpath(p, ROOT)}: the @Value constructor is @Autowired",
              "two-constructor @Component without @Autowired fails the context at boot")
        check("2026-" not in re.sub(r"/\*\*.*?\*/", "", src, flags=re.S),
              f"{os.path.relpath(p, ROOT)}: carries NO hardcoded calendar dates in code")
else:
    check(False, "BusinessCalendar: both service copies exist")

# Both batch services must persist a run ledger and reach the ops-alert pipeline.
for svc, table in [("settlement-reconciliation", "batch_runs"),
                   ("scheme-adapter-zeropay", "zp_batch_runs")]:
    migs = glob.glob(os.path.join(ROOT, "services", svc, "src", "main", "resources", "db",
                                  "migration", "V*.sql"))
    check(any(table in read(m) for m in migs),
          f"{svc}: a Flyway migration creates the {table} ledger")
    versions = [os.path.basename(m).split("__")[0] for m in migs]
    check(len(versions) == len(set(versions)),
          f"{svc}: Flyway versions are unique", ", ".join(sorted(versions)))
    java = "\n".join(read(f) for f in glob.glob(
        os.path.join(ROOT, "services", svc, "src", "main", "java", "**", "*.java"), recursive=True))
    check("Propagation.REQUIRES_NEW" in java,
          f"{svc}: the run recorder commits in its own transaction (survives the job's rollback)")
    check("BATCH_RUN_FAILED" in java and "OpsAlertPayload" in java,
          f"{svc}: batch failures are raised through the existing ops.alert pipeline")
    check("BATCH_CALENDAR_UNVERIFIED" in java,
          f"{svc}: an unverified business day is alerted, not silently assumed")

check(os.path.exists(os.path.join(ROOT, "Documentation", "RUNBOOK_BATCH_OPS.md")),
      "Documentation/RUNBOOK_BATCH_OPS.md exists (operator contract for the calendar + re-runs)")

# ---------------------------------------------------------------------------
for ok, label, detail in checks:
    print(("ok   " if ok else "FAIL ") + label + ("" if ok or not detail else " - " + detail))

print(f"\n{len(checks) - len(failures)}/{len(checks)} checks passed")
if failures:
    print("\n%d MISMATCH(ES):\n- %s" % (len(failures), "\n- ".join(failures)), file=sys.stderr)
    sys.exit(1)
