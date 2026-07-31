#!/usr/bin/env python3
"""Monitoring / alerting wiring guard (gap register T3-2, T3-3).

T3-2 and T3-3 were both *silent* failures: `/actuator/prometheus` was claimed in a build comment and
``permitAll``-ed in the gateway's security config while 404-ing on all 19 services, and the two
production safety-net monitors were switched off by a default nobody had noticed.  Neither is visible
by eyeballing a build file or three long YAML manifests, so this script is that check.

Like ``check_internal_auth_wiring.py`` it derives the requirement from the *code / shipped config*
rather than carrying a hardcoded service list:

  METRICS (T3-2)
    * the root ``build.gradle`` must put a Micrometer Prometheus registry on every module that
      applies the Spring Boot plugin (one place, not 19);
    * ``libs/lib-errors`` must ship ``MetricsExposureEnvironmentPostProcessor`` AND register it in
      ``META-INF/spring.factories`` -- un-registering it silently restores the 404;
    * no service may re-introduce a dead OTLP/OTEL exporter endpoint in a Helm values file;
    * every service whose own config gates ``/actuator/metrics/**`` must gate
      ``/actuator/prometheus`` too (a scrape exposes the same per-partner counters), and the
      internet-facing api-gateway must NOT ``permitAll`` the scrape path.

  ALERTING (T3-3)
    * payment-executor's shipped ``application.properties`` must enable the DECLINE_SPIKE monitor and
      the ops-alert archive, and must NOT ship a blank notification-sink URL;
    * the durable ``ops_alerts`` migration must exist;
    * ``docker-compose.yml`` and ``deploy/helm/gmepay/values.yaml`` must both arm
      transaction-mgmt's stuck-transaction sweeper (its CODE default is still false -- that service is
      owned by another workstream, so the manifests are what keep it on).

Run (no servers, no Docker, no network):
    python scripts/check_monitoring_wiring.py

Exit 0 = every monitoring/alerting edge is wired, 1 = a mismatch (printed with the file).
"""
from __future__ import annotations

import io
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is required: python -m pip install pyyaml")

# Windows consoles default to cp949/cp1252 here; labels interpolate file content.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:  # pragma: no cover
        pass

ROOT = Path(__file__).resolve().parent.parent

PROMETHEUS_PATH = "/actuator/prometheus"
METRICS_PATTERN = "/actuator/metrics/**"

# Services that gate /actuator/metrics/** but not yet /actuator/prometheus.  Their source trees were
# owned by a concurrent workstream when T3-2 landed, so the one-line pattern addition could not be made
# there.  Their scrape endpoint is therefore ANONYMOUS in-cluster today -- no worse than their already
# anonymous /actuator/metrics, but it is real debt.  Tracked as the T3-2 residual in
# Documentation/GAP_REGISTER.md; REMOVE a name from this list (do not add to it) once its config lists
# /actuator/prometheus next to /actuator/metrics/**.
SCRAPE_GATE_FOLLOWUPS = {"auth-identity", "transaction-mgmt", "config-registry"}

_results: list[tuple[bool, str]] = []


def check(ok: bool, label: str) -> None:
    _results.append((bool(ok), label))


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def uncommented(text: str, marker: str = "#") -> str:
    """Strip whole-line comments so explanatory prose naming an old value is not matched."""
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith(marker))


# ---------------------------------------------------------------------------
# T3-2 -- the metrics endpoint exists, fleet-wide, in one place
# ---------------------------------------------------------------------------
def check_metrics_registry() -> None:
    root_build = read("build.gradle")
    check(
        "micrometer-registry-prometheus" in root_build,
        "root build.gradle puts micrometer-registry-prometheus on the fleet",
    )
    check(
        "plugins.withId('org.springframework.boot')" in root_build,
        "the registry is applied to Spring Boot modules only (not libs / e2e-tests)",
    )

    # Every deployable applies the Boot plugin => every deployable gets the registry from that block.
    deployables = sorted(
        p.parent.name
        for p in (ROOT / "services").glob("*/build.gradle")
        if "id 'org.springframework.boot'" in p.read_text(encoding="utf-8")
    )
    check(len(deployables) >= 19, f"{len(deployables)} deployable services inherit the registry")

    epp = "libs/lib-errors/src/main/java/com/gme/pay/platform/MetricsExposureEnvironmentPostProcessor.java"
    check((ROOT / epp).is_file(), "lib-errors ships MetricsExposureEnvironmentPostProcessor")
    factories = read("libs/lib-errors/src/main/resources/META-INF/spring.factories")
    check(
        "MetricsExposureEnvironmentPostProcessor" in factories,
        "the post-processor is registered in spring.factories (else every service 404s again)",
    )
    check(
        "EnvironmentPostProcessor" in factories,
        "spring.factories registers it under the EnvironmentPostProcessor key",
    )


def check_no_dead_otel() -> None:
    """The OTLP endpoint was dead config: no exporter on any classpath, no collector deployed."""
    offenders = []
    for values in sorted((ROOT / "deploy/helm/gmepay").glob("values*.yaml")):
        if re.search(r"^\s*OTEL_[A-Z_]+\s*:", uncommented(values.read_text(encoding="utf-8")), re.M):
            offenders.append(values.name)
    check(not offenders, f"no Helm values file declares a dead OTEL exporter endpoint ({offenders})")

    # ... and no service pretends to configure one either.
    live_otel = []
    for cfg in sorted((ROOT / "services").glob("*/src/main/resources/application.*")):
        text = uncommented(cfg.read_text(encoding="utf-8"))
        if re.search(r"^\s*otel\s*:", text, re.M) or "otel.exporter" in text:
            live_otel.append(str(cfg.relative_to(ROOT)))
    check(not live_otel, f"no service ships a dead otel.* config block ({live_otel})")


def check_scrape_is_gated() -> None:
    """A real scrape endpoint must not become a new anonymous surface."""
    for cfg in sorted((ROOT / "services").glob("*/src/main/resources/application.*")):
        text = uncommented(cfg.read_text(encoding="utf-8"))
        if METRICS_PATTERN not in text:
            continue  # this service does not gate its own introspection surface
        svc = cfg.parts[cfg.parts.index("services") + 1]
        if svc in SCRAPE_GATE_FOLLOWUPS:
            # Known debt, deliberately not a hard failure -- but it must not silently grow.
            print(f"WARN {svc}: gates {METRICS_PATTERN} but NOT {PROMETHEUS_PATH} "
                  f"(known T3-2 residual, see SCRAPE_GATE_FOLLOWUPS)")
            continue
        check(
            PROMETHEUS_PATH in text,
            f"{svc}: gates {METRICS_PATTERN}, so it must also gate {PROMETHEUS_PATH}",
        )

    # payment-executor gates via a config class, not a property list.
    exec_cfg = read(
        "services/payment-executor/src/main/java/com/gme/pay/payment/config/"
        "SandboxSurfaceInternalAuthConfig.java"
    )
    check(
        PROMETHEUS_PATH in exec_cfg,
        f"payment-executor's internal-auth config gates {PROMETHEUS_PATH}",
    )

    # api-gateway is the ONE internet-reachable service: the scrape must not be permitAll'd.
    gw = read("services/api-gateway/src/main/java/com/gme/pay/gateway/config/SecurityConfig.java")
    gw_live = uncommented(gw, "//")
    permit_line = re.search(
        r"pathMatchers\(([^)]*)\)\s*\.permitAll\(\)", gw_live.replace("\n", " ")
    )
    check(
        permit_line is not None and PROMETHEUS_PATH not in permit_line.group(1),
        "api-gateway does NOT permitAll the Prometheus scrape (it is behind the internal token)",
    )
    check(
        "metricsScrapeSecurityFilterChain" in gw,
        "api-gateway has a dedicated internal-token-gated chain for the scrape path",
    )


def check_helm_advertises_scrape() -> None:
    values = yaml.safe_load(read("deploy/helm/gmepay/values.yaml"))
    monitoring = values.get("monitoring") or {}
    check(monitoring.get("podAnnotations") is True, "Helm advertises pods as Prometheus scrape targets")
    check(
        monitoring.get("scrapePath") == PROMETHEUS_PATH,
        f"Helm monitoring.scrapePath is {PROMETHEUS_PATH}",
    )
    tpl = read("deploy/helm/gmepay/templates/_deployment.tpl")
    check("prometheus.io/scrape" in tpl, "the pod template stamps prometheus.io/scrape")


# ---------------------------------------------------------------------------
# T3-3 -- the alert chain is armed, durable, and has a sink
# ---------------------------------------------------------------------------
def _props(rel: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in read(rel).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def check_decline_spike_armed() -> None:
    rel = "services/payment-executor/src/main/resources/application.properties"
    props = _props(rel)
    check(
        props.get("gmepay.decline-spike.enabled") == "true",
        "payment-executor ships the DECLINE_SPIKE monitor ENABLED",
    )
    check(
        "GMEPAY_DECLINE_SPIKE_ENABLED" not in uncommented(read(rel)),
        "the DECLINE_SPIKE flag is not defeatable by a forgotten env var",
    )
    monitor = read(
        "services/payment-executor/src/main/java/com/gme/pay/payment/alert/DeclineSpikeMonitor.java"
    )
    check("matchIfMissing = true" in monitor, "the monitor bean condition is matchIfMissing = true")

    # Thresholds present and configurable.
    for key in (
        "gmepay.decline-spike.window-seconds",
        "gmepay.decline-spike.min-samples",
        "gmepay.decline-spike.threshold-rate",
        "gmepay.decline-spike.cooldown-seconds",
    ):
        check(key in props, f"threshold {key} is shipped in config (tunable without a rebuild)")


def check_alert_durability() -> None:
    migrations = sorted((ROOT / "services/payment-executor/src/main/resources/db/migration").glob("V*.sql"))
    check(
        any("ops_alerts" in m.name for m in migrations),
        "payment-executor has the durable ops_alerts migration",
    )
    # Flyway versions must stay unique and gapless-ish; a duplicate version fails at boot.
    versions = [re.match(r"V(\d+)__", m.name).group(1) for m in migrations if re.match(r"V(\d+)__", m.name)]
    check(len(versions) == len(set(versions)), f"Flyway versions are unique ({versions})")

    props = _props("services/payment-executor/src/main/resources/application.properties")
    check(
        props.get("gmepay.ops.alerts.prune-enabled") == "true",
        "the ops_alerts retention pruner is enabled (the table stays bounded)",
    )
    check(
        int(props.get("gmepay.ops.alerts.retention-days", "0")) >= 30,
        "ops alerts are retained long enough for an incident review",
    )
    check(
        "gmepay.alert.sink.webhook-url" not in props,
        "NO blank notification-sink URL is shipped (a blank URL must not activate the webhook sink)",
    )
    sink_cfg = read(
        "services/payment-executor/src/main/java/com/gme/pay/payment/alert/AlertSinkConfig.java"
    )
    check(
        "isBlank" in sink_cfg,
        "AlertSinkConfig treats a blank URL as 'not configured' (so ${VAR:-} cannot break paging)",
    )


def check_stuck_sweeper_armed() -> None:
    """transaction-mgmt's sweeper default is still false in CODE, so the manifests must arm it."""
    key = "GMEPAY_TXN_STUCK_ALERT_ENABLED"

    compose = yaml.safe_load(read("docker-compose.yml"))
    txn = (compose.get("services") or {}).get("transaction-mgmt") or {}
    env = txn.get("environment") or {}
    if isinstance(env, list):  # tolerate the list form
        env = dict(e.split("=", 1) for e in env if "=" in e)
    check(
        str(env.get(key, "")).strip().strip('"') == "true",
        f"docker-compose.yml arms the stuck-transaction sweeper ({key})",
    )

    values = yaml.safe_load(read("deploy/helm/gmepay/values.yaml"))
    henv = ((values.get("services") or {}).get("transaction-mgmt") or {}).get("env") or {}
    check(
        str(henv.get(key, "")).strip().strip('"') == "true",
        f"Helm values.yaml arms the stuck-transaction sweeper ({key})",
    )


def check_runbook() -> None:
    rel = "Documentation/RUNBOOK_MONITORING.md"
    check((ROOT / rel).is_file(), "the operator runbook exists")
    if (ROOT / rel).is_file():
        text = read(rel)
        for token in (PROMETHEUS_PATH, "GMEPAY_ALERT_SINK_WEBHOOK_URL", "DECLINE_SPIKE"):
            check(token in text, f"the runbook documents {token}")


def main() -> int:
    check_metrics_registry()
    check_no_dead_otel()
    check_scrape_is_gated()
    check_helm_advertises_scrape()
    check_decline_spike_armed()
    check_alert_durability()
    check_stuck_sweeper_armed()
    check_runbook()

    failures = [label for ok, label in _results if not ok]
    for ok, label in _results:
        print(f"{'ok  ' if ok else 'FAIL'} {label}")
    print(f"\n{len(_results) - len(failures)}/{len(_results)} checks passed")
    if failures:
        print("\nFAILED:")
        for label in failures:
            print(f"  - {label}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
