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

Section 8 guards ``docker-compose.yml`` instead of the chart: **no two services may publish the
same host port.** Compose is the fleet manifest this repo actually starts from, and a duplicate is
not a warning -- the second bind fails, so ``docker compose up`` cannot bring the profile up at all.
``scheme-adapter-nepal`` and ``settlement-reconciliation`` both published ``8092`` for months
(fixed in 31c4397 by moving settlement-reconciliation to ``8100``); nothing in the repo would have
said so. Uniqueness is asserted GLOBALLY, not per profile: services here overlap across profiles,
and a port shared by two blocks is a latent break the moment both are selected.

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


PROTO = re.compile(r"/(?:tcp|udp|sctp)$", re.I)
IPV6_BIND = re.compile(r"^\[[0-9A-Fa-f:.]*\]:")


def _expand_host_spec(spec: str) -> tuple[list[int], str | None]:
    """'8092' -> [8092]; '8000-8002' -> [8000, 8001, 8002]. -> (ports, error)."""
    spec = spec.strip()
    if not spec:
        return [], None
    if spec.isdigit():
        return [int(spec)], None
    m = re.fullmatch(r"(\d+)-(\d+)", spec)
    if m:
        lo, hi = int(m.group(1)), int(m.group(2))
        if lo > hi:
            return [], f"inverted host port range '{spec}'"
        return list(range(lo, hi + 1)), None
    return [], f"unparseable host port '{spec}'"


def host_ports(entry: object) -> tuple[list[int], str | None]:
    """Host ports a single compose ``ports:`` entry CLAIMS -> (ports, error).

    Handles short syntax ('8092:8080', '127.0.0.1:8092:8080', '[::1]:8092:8080', ranges,
    a trailing '/tcp') and long syntax ({published:, target:}).

    A container-only entry ('8080') claims NO fixed host port -- Docker picks an ephemeral one --
    so it correctly returns []. An entry this function cannot READ returns an error instead of an
    empty list: silently skipping an unrecognised form is exactly how a duplicate would slip back
    in past this guard.
    """
    if isinstance(entry, dict):                                   # long syntax
        pub = entry.get("published")
        if pub is None:
            return [], None                                       # target-only -> ephemeral
        return _expand_host_spec(str(pub))
    if isinstance(entry, bool):
        return [], f"unrecognised ports entry {entry!r}"
    if isinstance(entry, int):
        return [], None                                           # bare container port
    if not isinstance(entry, str):
        return [], f"unrecognised ports entry type {type(entry).__name__}"

    text = PROTO.sub("", entry.strip())
    text = IPV6_BIND.sub("BIND:", text)                           # keep the field count, drop ':'s
    parts = text.split(":")
    if len(parts) == 1:
        return [], None                                           # '8080' -> container only
    if len(parts) == 2:
        return _expand_host_spec(parts[0])                        # host:container
    if len(parts) == 3:
        return _expand_host_spec(parts[1])                        # bindIP:host:container
    return [], f"unrecognised ports entry '{entry}'"


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
print("                          T3-4 (batch-ops calendar), compose host-port uniqueness")
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
# 7. T5-7 - the 9Pay IPN edge is source-restricted, ships CLOSED, and ships EMPTY
# ---------------------------------------------------------------------------
# T5-6 cannot be fixed in code we own (9Pay leaves the IPN `code` field outside its signed
# string, so a captured success IPN with `code` rewritten to a reversal still verifies).  This
# allowlist is the only compensating control, which makes three properties load-bearing:
#   (a) the edge exists as its own Ingress, restricted to EXACTLY /scheme/ipn -- the same
#       Service also serves /scheme/payout, so a `/` Prefix rule would publish a payout
#       submission endpoint;
#   (b) enabling it with no ranges must FAIL, not open to 0.0.0.0/0;
#   (c) NO 9Pay address may be committed anywhere in the chart or the service config -- the
#       ranges are partner-supplied, differ per estate, and a stale hardcoded range would
#       fail closed against real traffic.
print("\n-- 7. T5-7: 9Pay IPN edge source allowlist --------------------------------")
IPN_TPL = os.path.join(HELM, "templates", "ingress-ipn.yaml")
check(os.path.exists(IPN_TPL),
      "chart: templates/ingress-ipn.yaml exists (the IPN edge is its own restricted Ingress)")
if os.path.exists(IPN_TPL):
    ipn_tpl = read(IPN_TPL)
    check("{{- fail " in ipn_tpl and "sourceRanges" in ipn_tpl,
          "ingress-ipn.yaml: enabling the edge with an EMPTY sourceRanges FAILS the template",
          "without the fail guard, a forgotten value silently publishes the IPN edge")
    check("pathType: Exact" in ipn_tpl and "pathType: Prefix" not in ipn_tpl,
          "ingress-ipn.yaml: routes EXACTLY /scheme/ipn (Prefix would publish /scheme/payout)")
    check("whitelist-source-range" in ipn_tpl and "inbound-cidrs" in ipn_tpl,
          "ingress-ipn.yaml: renders the controller-appropriate allowlist annotation",
          "nginx and ALB use different keys; the wrong key is a silent no-op")

for path in [os.path.join(HELM, "values.yaml")] + [os.path.join(HELM, o) for o in OVERLAYS]:
    name = os.path.basename(path)
    doc = yaml.safe_load(read(path)) or {}
    ipn = doc.get("ipnIngress")
    check(isinstance(ipn, dict), f"{name}: declares an ipnIngress block")
    if not isinstance(ipn, dict):
        continue
    check(ipn.get("enabled") is False,
          f"{name}: ipnIngress ships DISABLED (opening the edge is a deliberate act)",
          f"got enabled={ipn.get('enabled')!r}")
    check(not (ipn.get("sourceRanges") or []),
          f"{name}: ipnIngress.sourceRanges is EMPTY in git (9Pay's ranges are partner data)",
          f"got {ipn.get('sourceRanges')!r} - never commit a partner's IP ranges as a default")

# The service-level layer exists, is wired, and also ships empty.
NP = os.path.join(ROOT, "services", "scheme-adapter-ninepay")
np_filter = os.path.join(NP, "src", "main", "java", "com", "gme", "pay", "scheme", "ninepay",
                         "api", "NinepayIpnSourceFilter.java")
check(os.path.exists(np_filter),
      "scheme-adapter-ninepay: NinepayIpnSourceFilter exists (service-level layer for direct reach)")
np_yaml = read(os.path.join(NP, "src", "main", "resources", "application.yml"))
check("allowed-source-ranges: ${GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES:}" in np_yaml,
      "scheme-adapter-ninepay: the shipped allowlist default is EMPTY (env-supplied, never baked in)")
np_env = ((chart_services.get("scheme-adapter-ninepay") or {}).get("env") or {})
for var in ("GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES",
            "GMEPAY_SCHEME_NINEPAY_IPN_TRUSTED_PROXY_COUNT"):
    check(var in np_env, f"chart: scheme-adapter-ninepay declares {var}")
check(str(np_env.get("GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES", "x")) == "",
      "chart: scheme-adapter-ninepay ships the IPN allowlist EMPTY (operator/partner input)")

# No 9Pay address anywhere in the chart or the adapter's shipped config. The addresses that
# 9Pay's API document listed are recorded ONCE, in Documentation/schemes/ (a digest of the
# partner doc), which is where a reference belongs; a deployable default is a different thing.
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
ALLOWED_LITERALS = {"0.0.0.0", "127.0.0.1", "255.255.255.255", "1.2.3.4"}
for path in ([os.path.join(HELM, "values.yaml")] + [os.path.join(HELM, o) for o in OVERLAYS]
             + [IPN_TPL, os.path.join(NP, "src", "main", "resources", "application.yml")]):
    if not os.path.exists(path):
        continue
    found = sorted({ip for ip in IPV4.findall(strip_comments(read(path)))
                    if ip not in ALLOWED_LITERALS})
    check(not found,
          f"{os.path.basename(path)}: carries NO hardcoded partner IP address",
          ", ".join(found))

# ---------------------------------------------------------------------------
# 8. docker-compose.yml - host-port uniqueness
# ---------------------------------------------------------------------------
# Not a chart check, but the same class of defect: a manifest that cannot deploy what the code
# needs. A duplicate host port is silent in review and fatal at `up` (the second bind loses), and
# it survived here for months. Asserted globally rather than per profile - see the module docstring.
print("\n-- 8. docker-compose host-port uniqueness ---------------------------------")
COMPOSE = os.path.join(ROOT, "docker-compose.yml")
compose = yaml.safe_load(read(COMPOSE)) or {}
compose_services = compose.get("services") or {}
check(isinstance(compose_services, dict) and bool(compose_services),
      "docker-compose.yml parses and declares services (PyYAML)")

claims: dict[int, list[str]] = {}
for svc in sorted(compose_services if isinstance(compose_services, dict) else {}):
    block = compose_services[svc]
    if not isinstance(block, dict):
        continue
    entries = block.get("ports") or []
    if not isinstance(entries, list):
        check(False, f"compose: {svc} 'ports' is a list", f"got {type(entries).__name__}")
        continue
    for entry in entries:
        ports, err = host_ports(entry)
        if err is not None:
            check(False, f"compose: {svc} ports entry is a form this guard can read", err)
        for port in ports:
            claims.setdefault(port, []).append(svc)

collisions = {p: s for p, s in sorted(claims.items()) if len(s) > 1}
check(not collisions,
      "compose: no host port is published by two services (`docker compose up` would fail)",
      "; ".join(f"{p} <- {', '.join(s)}" for p, s in collisions.items()))
check(bool(claims), "compose: the guard actually read some published host ports",
      "parsed zero host ports - the ports syntax changed and this check went blind")

# ---------------------------------------------------------------------------
# 8b. Horizontal scaling: the chart is CAPABLE of it and ships it OFF
# ---------------------------------------------------------------------------
# The replica-ceiling work made N>1 correct (shared rate-limit / replay / idempotency / ops-alert
# state, ShedLock on every scheduled job). This section asserts the chart now *offers* scaling
# without *choosing* it: how many replicas to run is a cost decision belonging to the owner, and a
# plausible-looking maxReplicas / CPU target committed here would be read as an engineering
# position. So: the template exists, nothing is enabled anywhere in git, every service still
# resolves to one replica, and the Deployment yields spec.replicas to an HPA when one exists (a
# hardcoded replica count under an HPA fights it on every `helm upgrade`).
print("\n-- 8b. horizontal scaling: capable, and shipped OFF -----------------------")
HPA_TPL = os.path.join(HELM, "templates", "hpa.yaml")
check(os.path.exists(HPA_TPL), "chart: templates/hpa.yaml exists (scaling is expressible at all)")
if os.path.exists(HPA_TPL):
    hpa_tpl = read(HPA_TPL)
    check("kind: HorizontalPodAutoscaler" in hpa_tpl and "autoscaling/v2" in hpa_tpl,
          "hpa.yaml: renders an autoscaling/v2 HorizontalPodAutoscaler")
    check(hpa_tpl.count("{{- fail ") >= 2,
          "hpa.yaml: enabling autoscaling without maxReplicas or a metric FAILS the template",
          "without the fail guards the chart would have to invent a ceiling and a CPU target")
    check("gmepay.autoscalingEnabled" in hpa_tpl,
          "hpa.yaml: uses the shared enablement helper (so it cannot disagree with the Deployment)")

check('include "gmepay.autoscalingEnabled"' in deployment_tpl
      and "replicas: {{ $svc.replicas" in deployment_tpl,
      "_deployment.tpl: spec.replicas is OMITTED when an HPA manages the Deployment",
      "a hardcoded replica count under an HPA is reset on every helm upgrade")
check('define "gmepay.autoscalingEnabled"' in read(os.path.join(HELM, "templates", "_helpers.tpl")),
      "_helpers.tpl: defines gmepay.autoscalingEnabled")

for path in [os.path.join(HELM, "values.yaml")] + [os.path.join(HELM, o) for o in OVERLAYS]:
    name = os.path.basename(path)
    doc = yaml.safe_load(read(path)) or {}
    auto = doc.get("autoscaling")
    if auto is not None:
        check(auto.get("enabled") is False,
              f"{name}: autoscaling ships DISABLED (replica count is an owner's cost decision)",
              f"got enabled={auto.get('enabled')!r}")
    svcs = doc.get("services") or {}
    for svc_name, entry in sorted(svcs.items()):
        if not isinstance(entry, dict):
            continue
        per_svc = entry.get("autoscaling") or {}
        check(not per_svc.get("enabled"),
              f"{name}: services.{svc_name} does not enable autoscaling in git")
        replicas = entry.get("replicas")
        check(replicas is None or int(replicas) == 1,
              f"{name}: services.{svc_name} ships 1 replica",
              f"got replicas={replicas!r} - turning scaling ON is a deliberate operator act")

check(int(((base.get("global") or {}).get("defaultReplicas", 0))) == 1,
      "values.yaml: global.defaultReplicas is 1 (the floor stays 1 until an owner raises it)")

# ---------------------------------------------------------------------------
for ok, label, detail in checks:
    print(("ok   " if ok else "FAIL ") + label + ("" if ok or not detail else " - " + detail))

print(f"\n{len(checks) - len(failures)}/{len(checks)} checks passed")
if failures:
    print("\n%d MISMATCH(ES):\n- %s" % (len(failures), "\n- ".join(failures)), file=sys.stderr)
    sys.exit(1)
