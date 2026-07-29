#!/usr/bin/env python3
"""Internal-auth / fleet-config wiring guard (gap register T0-2, T0-5).

The security hardening made four services FAIL CLOSED on a missing
``GMEPAY_INTERNAL_AUTH_SECRET`` (they refuse to boot) and made every gated edge answer 401 to a
caller that omits it.  A single missing entry in a deployment manifest therefore means a service
silently will not start, or a money path silently declines — neither of which is visible by
eyeballing three long YAML files.  This script is that check.

It does NOT carry a hardcoded list of services.  The requirement is derived from the *code*:

  * a service REQUIRES the secret if its shipped ``application.properties`` / ``application.yml``
    resolves a property from ``${GMEPAY_INTERNAL_AUTH_SECRET...}`` — that is the service telling us
    it reads the variable;
  * it is additionally classified BOOT-CRITICAL if its main sources contain a fail-closed guard
    (``refuses to start``), i.e. a blank secret aborts context refresh;
  * separately, any service whose main sources send the ``X-Gme-Internal`` header is a CALLER and
    must have a secret property declared in its own config — otherwise the wiring is broken at the
    source and no manifest can fix it.

Each required service is then asserted present in:

  * ``docker-compose.yml``                  (env key on that service's own block)
  * ``deploy/helm/gmepay/values.yaml``      (envSecretKeys, or an envSecretAliases target)
  * the three Helm overlays                 (inherited — asserted NOT clobbered, plus a placeholder)
  * ``run-fleet.ps1``                       (exported into the child JVM environment)

Plus the T1-2 residual: ``run-fleet.ps1`` must pin ``OIDC_ISSUER_URI``, because the Java default is
the stale ``:8090`` and a host-run ops-partner-bff otherwise 401s everything.

Run (no servers, no Docker):
    python scripts/check_internal_auth_wiring.py

Exit 0 = every gated edge is wired on every surface, 1 = a mismatch (printed with the file).
"""
from __future__ import annotations

import io
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is required: python -m pip install pyyaml")

# Windows consoles default to cp949/cp1252 here; the report text below is ASCII, but the
# labels interpolate file content, so force UTF-8 rather than crash on a stray glyph.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_VAR = "GMEPAY_INTERNAL_AUTH_SECRET"
HEADER = "X-Gme-Internal"
HELM = os.path.join("deploy", "helm", "gmepay")
OVERLAYS = ["values-onprem.yaml", "values-aws.yaml", "values-azure.yaml"]

failures: list[str] = []
checks: list[tuple[bool, str, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    checks.append((bool(ok), label, detail))
    if not ok:
        failures.append(label + (f" - {detail}" if detail else ""))


def read(rel: str) -> str:
    with io.open(os.path.join(REPO, rel), encoding="utf-8") as fh:
        return fh.read()


def walk_files(root: str, suffixes: tuple[str, ...]) -> list[str]:
    hits = []
    for dirpath, _dirs, files in os.walk(os.path.join(REPO, root)):
        for f in files:
            if f.endswith(suffixes):
                hits.append(os.path.join(dirpath, f))
    return hits


# ---------------------------------------------------------------------------
# 1. Derive, from the code, which services need the secret
# ---------------------------------------------------------------------------
services_dir = os.path.join(REPO, "services")
all_services = sorted(
    d for d in os.listdir(services_dir) if os.path.isdir(os.path.join(services_dir, d))
)

requires: dict[str, dict[str, bool]] = {}

for svc in all_services:
    cfg_text = ""
    for cfg in walk_files(os.path.join("services", svc, "src", "main", "resources"),
                          (".properties", ".yml", ".yaml")):
        with io.open(cfg, encoding="utf-8") as fh:
            cfg_text += fh.read() + "\n"

    java_text = ""
    for j in walk_files(os.path.join("services", svc, "src", "main", "java"), (".java",)):
        with io.open(j, encoding="utf-8") as fh:
            java_text += fh.read() + "\n"

    declares = ("${" + ENV_VAR) in cfg_text
    fail_closed = "refuses to start" in java_text
    # A caller = it puts the gate's header on an outbound request. Exclude the gate-side config
    # classes, which name the header only to build the filter's own pattern list.
    caller = bool(re.search(r"defaultHeader\(\s*(?:com\.gme\.pay\.internalauth\.)?"
                            r"InternalAuthHeaders\.INTERNAL_TOKEN", java_text)) or \
        bool(re.search(r'\.set\(\s*"' + HEADER + r'"', java_text)) or \
        bool(re.search(r'header\(\s*(?:com\.gme\.pay\.internalauth\.)?'
                       r'InternalAuthHeaders\.INTERNAL_TOKEN', java_text))

    if declares or caller:
        requires[svc] = {"declares": declares, "fail_closed": fail_closed and declares,
                         "caller": caller}

print("Derived from code - services that need %s:" % ENV_VAR)
for svc, f in sorted(requires.items()):
    tags = []
    if f["fail_closed"]:
        tags.append("BOOT-CRITICAL")
    if f["caller"]:
        tags.append("caller")
    if f["declares"]:
        tags.append("declares-property")
    print("  %-28s %s" % (svc, ", ".join(tags)))
print()

check(len(requires) >= 8, "code scan found the gated fleet (>=8 services)", str(len(requires)))

# A caller that never declares the property can never be fixed from a manifest.
for svc, f in sorted(requires.items()):
    if f["caller"]:
        check(f["declares"],
              f"{svc}: sends {HEADER} AND declares a secret property from ${ENV_VAR}",
              "the client would always send a blank token — fix the service's application config")


# ---------------------------------------------------------------------------
# 2. docker-compose.yml
# ---------------------------------------------------------------------------
compose_text = read("docker-compose.yml")
compose = yaml.safe_load(compose_text)
check(isinstance(compose, dict) and "services" in compose, "docker-compose.yml parses (PyYAML)")

for svc, f in sorted(requires.items()):
    block = (compose.get("services") or {}).get(svc)
    if block is None:
        check(True, f"compose: {svc} is not a compose service (skipped)")
        continue
    env = block.get("environment") or {}
    keys = set(env.keys()) if isinstance(env, dict) else {
        e.split("=", 1)[0] for e in env
    }
    why = "REFUSES TO BOOT without it" if f["fail_closed"] else "its gated calls 401 without it"
    check(ENV_VAR in keys, f"compose: {svc} sets {ENV_VAR}", why)

# The literal must exist exactly once (the anchor) — T0-6 stays a one-line fix.
inline = len(re.findall(r"dev-internal-svc-secret-not-for-prod", compose_text))
check(inline == 1, "compose: the dev-default literal appears exactly once (the shared anchor)",
      f"found {inline} — do not re-inline it in a service block")
check("&internal-auth-secret" in compose_text,
      "compose: the shared internal-auth anchor is defined")


# ---------------------------------------------------------------------------
# 3. Helm — base values + overlays
# ---------------------------------------------------------------------------
base = yaml.safe_load(read(os.path.join(HELM, "values.yaml")))
check(isinstance(base, dict) and "services" in base, "Helm values.yaml parses (PyYAML)")

check((base.get("secrets") or {}).get("data", {}).get(ENV_VAR) is not None,
      f"Helm: {ENV_VAR} is declared in secrets.data (the chart's placeholder mechanism)")

for svc, f in sorted(requires.items()):
    entry = (base.get("services") or {}).get(svc)
    if entry is None:
        check(True, f"Helm: {svc} is not a chart service (skipped)")
        continue
    keys = set(entry.get("envSecretKeys") or [])
    aliases = entry.get("envSecretAliases") or {}
    ok = ENV_VAR in keys or ENV_VAR in set(aliases.values())
    why = "REFUSES TO BOOT without it" if f["fail_closed"] else "its gated calls 401 without it"
    check(ok, f"Helm values.yaml: {svc} pulls {ENV_VAR} from the Secret", why)
    # The exact env name the service's config reads must be present, not only an alias under
    # another name: relaxed binding maps GMEPAY_AUTH_IDENTITY_INTERNAL_SECRET onto
    # gmepay.auth-identity.internal-secret only, never onto gmepay.internal-auth.secret.
    if f["declares"]:
        check(ENV_VAR in keys,
              f"Helm values.yaml: {svc} surfaces {ENV_VAR} under its OWN name (not only an alias)",
              f"envSecretKeys={sorted(keys)} aliases={aliases}")

for ov in OVERLAYS:
    text = read(os.path.join(HELM, ov))
    y = yaml.safe_load(text) or {}
    check(isinstance(y, dict), f"Helm {ov} parses (PyYAML)")
    placeholder = (y.get("secrets") or {}).get("data", {}).get(ENV_VAR)
    check(bool(placeholder) and placeholder.startswith("REPLACE"),
          f"Helm {ov}: {ENV_VAR} is a REPLACE_* placeholder, not a working credential",
          str(placeholder))
    # Helm merges `env` maps key-by-key but a LIST value replaces the base list outright, so an
    # overlay that redeclares envSecretKeys would silently drop the secret for that service.
    for svc, entry in (y.get("services") or {}).items():
        if entry and entry.get("envSecretKeys") is not None and svc in requires:
            check(ENV_VAR in set(entry["envSecretKeys"]),
                  f"Helm {ov}: {svc} overrides envSecretKeys and must re-list {ENV_VAR}",
                  "a list override REPLACES the base list")


# ---------------------------------------------------------------------------
# 4. run-fleet.ps1 — one export reaches every child JVM
# ---------------------------------------------------------------------------
fleet = read("run-fleet.ps1")
code_lines = "\n".join(l for l in fleet.split("\n") if not l.strip().startswith("#"))

check(re.search(r"\$env:" + ENV_VAR + r"\s*=", code_lines) is not None,
      f"run-fleet.ps1: exports {ENV_VAR} into the child JVM environment",
      "Start-Process inherits the script process's env, so one assignment covers every service")
check(re.search(r"\$env:OIDC_ISSUER_URI\s*=", code_lines) is not None,
      "run-fleet.ps1: pins OIDC_ISSUER_URI (T1-2 residual)",
      "the Java default is the stale :8090 = scheme-adapter-zeropay, so the BFF 401s everything")

issuer = re.search(r"\$env:OIDC_ISSUER_URI\s*=\s*'([^']+)'", code_lines)
check(issuer is not None and issuer.group(1) == "http://localhost:8097/realms/gmepay",
      "run-fleet.ps1: OIDC_ISSUER_URI matches the canonical local topology (:8097 / realm gmepay)",
      issuer.group(1) if issuer else "not found")

# Every service run-fleet launches that is BOOT-CRITICAL must actually be in the fleet list, or the
# export protects nothing.
for svc, f in sorted(requires.items()):
    if f["fail_closed"] and re.search(r"name\s*=\s*'" + re.escape(svc) + r"'", fleet):
        check(True, f"run-fleet.ps1: {svc} is in the fleet and inherits the exported secret")


# ---------------------------------------------------------------------------
# 5. e2e-tests must present the header where it calls gated services
# ---------------------------------------------------------------------------
e2e = walk_files(os.path.join("e2e-tests", "src", "test", "java"), (".java",))
e2e_text = {os.path.basename(p): io.open(p, encoding="utf-8").read() for p in e2e}

harness = e2e_text.get("SchemeFleet.java", "")
check(ENV_VAR in harness, "e2e-tests: SchemeFleet exports the secret to every launched component")
check(HEADER in harness, "e2e-tests: SchemeFleet's HTTP helpers present the internal header")

for name, text in sorted(e2e_text.items()):
    if "ProcessBuilder" not in text or name == "SchemeFleet.java":
        continue
    # A suite with its own launcher must inject the env itself.
    check("INTERNAL_AUTH_ENV" in text or ENV_VAR in text,
          f"e2e-tests: {name} has its own launcher and injects the internal-auth env",
          "a gated service in its fleet would exit during startup")
    check("INTERNAL_HEADER" in text or HEADER in text,
          f"e2e-tests: {name} presents the internal header on its own HTTP helpers")


# ---------------------------------------------------------------------------
for ok, label, detail in checks:
    print(("ok   " if ok else "FAIL ") + label + ("" if ok or not detail else " - " + detail))

print(f"\n{len(checks) - len(failures)}/{len(checks)} checks passed")
if failures:
    print("\n%d MISMATCH(ES):\n- %s" % (len(failures), "\n- ".join(failures)), file=sys.stderr)
    sys.exit(1)
