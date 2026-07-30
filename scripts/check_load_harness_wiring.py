#!/usr/bin/env python3
"""Load-harness safety guard (gap register T3-5).

The load/soak harness added for T3-5 is the only tool in this repo that exists to hammer a real money
path until something saturates.  Two properties keep it safe, and **both are invisible by eyeballing**
the files they live in:

  1. **It cannot run against anything but a local/dev target.**  ``LoadTargetGuard`` requires every URL
     to be local *and* the operator to pass ``--i-know-this-is-not-prod``.  A well-meaning refactor that
     added a public hostname to the accepted list, or made the acknowledgement flag optional, would not
     look wrong in a diff.

  2. **It never runs automatically.**  There is no JUnit test that invokes it and no CI step that calls
     ``:e2e-tests:loadTest``; the task is a ``JavaExec`` deliberately left out of ``check``/``build``.
     One ``dependsOn`` or one workflow line would silently turn every CI run into a load test against
     whatever happened to be reachable from the runner.

A third property is about honesty rather than safety:

  3. **The shipped SLO target file declares nothing.**  What latency/availability GMEPay+ promises a
     partner is a business decision (register item T3-5 is deliberately split).  If plausible-looking
     numbers appear in ``Documentation/SLO_TARGETS.properties`` they must be a real, owned declaration --
     not an invented default that makes every run look green.

Run (no servers, no Docker, no network, nothing started):
    python scripts/check_load_harness_wiring.py

Exit 0 = the harness is wired safely, 1 = a violation (printed with the file).
"""
from __future__ import annotations

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

LOAD_PKG = ROOT / "e2e-tests/src/test/java/com/gme/pay/e2e/load"
GUARD = LOAD_PKG / "LoadTargetGuard.java"
HARNESS = LOAD_PKG / "LoadHarness.java"
OPTIONS = LOAD_PKG / "LoadOptions.java"
GUARD_TEST = LOAD_PKG / "LoadTargetGuardTest.java"
TARGETS_TEST = LOAD_PKG / "SloTargetsTest.java"
E2E_BUILD = ROOT / "e2e-tests/build.gradle"
SLO_FILE = ROOT / "Documentation/SLO_TARGETS.properties"
RUNBOOK = ROOT / "Documentation/RUNBOOK_LOAD_AND_CAPACITY.md"
WORKFLOWS = ROOT / ".github/workflows"

ACK_FLAG = "--i-know-this-is-not-prod"
TASK_NAME = "loadTest"

# Hostname fragments that must never appear in the guard's accepted-host list. ".local" / ".localhost"
# / "docker.internal" are legitimately there; anything routable is not.
FORBIDDEN_IN_ALLOWLIST = (".com", ".net", ".io", ".co.kr", "gmepay", "gmeremit", "amazonaws", "azure")

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"{path.relative_to(ROOT)} is missing -- the T3-5 harness is incomplete")
        return ""
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. The guard still requires BOTH a local host and the acknowledgement flag
# ---------------------------------------------------------------------------
def check_guard() -> None:
    src = read(GUARD)
    if not src:
        return

    if f'ACK_FLAG = "{ACK_FLAG}"' not in src:
        fail(f"{GUARD.relative_to(ROOT)}: ACK_FLAG is no longer '{ACK_FLAG}' -- the runbook, the "
             f"gradle task doc and LoadTargetGuardTest all name that exact flag")

    # The acknowledgement must be a hard requirement, i.e. a missing flag adds a refusal reason.
    if not re.search(r"if\s*\(\s*!\s*acknowledged\s*\)", src):
        fail(f"{GUARD.relative_to(ROOT)}: the `if (!acknowledged)` refusal is gone -- a local-looking "
             f"host is not proof of anything (a tunnel or a hosts entry can put production on "
             f"localhost), so the explicit flag must stay mandatory")

    # Refusal must be by exception, never by a boolean the caller can ignore.
    if "throw new RefusedException" not in src:
        fail(f"{GUARD.relative_to(ROOT)}: refusal no longer throws -- a return value can be ignored "
             f"by a caller, an exception cannot")

    # The accepted-host allowlist must stay loopback-only.
    allowlist = re.search(r"LOCAL_HOSTS\s*=\s*Set\.of\((.*?)\);", src, re.S)
    suffixes = re.search(r"LOCAL_SUFFIXES\s*=\s*List\.of\((.*?)\);", src, re.S)
    if not allowlist or not suffixes:
        fail(f"{GUARD.relative_to(ROOT)}: could not find LOCAL_HOSTS / LOCAL_SUFFIXES -- this guard "
             f"cannot verify what the harness now accepts as 'local'")
    else:
        accepted = (allowlist.group(1) + suffixes.group(1)).lower()
        # Strip comments: the block deliberately explains itself, and the prose mentions hostnames.
        accepted = re.sub(r"//[^\n]*", "", accepted)
        for fragment in FORBIDDEN_IN_ALLOWLIST:
            if fragment in accepted:
                fail(f"{GUARD.relative_to(ROOT)}: '{fragment}' appears in the accepted-host list. "
                     f"The harness must only ever accept loopback / *.localhost / docker host "
                     f"aliases -- a routable hostname here would let a load run reach a real "
                     f"deployment.")

    if "PROD_MARKERS" not in src:
        fail(f"{GUARD.relative_to(ROOT)}: PROD_MARKERS veto is gone -- 'prod.localhost' and an "
             f"SSH-forwarded local port are exactly how a local-looking target turns out not to be")


# ---------------------------------------------------------------------------
# 2. Nothing runs the harness automatically
# ---------------------------------------------------------------------------
def check_never_automatic() -> None:
    # 2a. No workflow invokes the task.
    if not WORKFLOWS.is_dir():
        notes.append(".github/workflows/ not found -- skipped the CI-invocation check")
    else:
        for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
            text = workflow.read_text(encoding="utf-8")
            if TASK_NAME in text:
                fail(f"{workflow.relative_to(ROOT)}: mentions '{TASK_NAME}'. The load harness must "
                     f"never run in CI -- it drives a real money path at a configurable rate and "
                     f"needs a human to confirm the target with {ACK_FLAG}.")
            # Sanity: the workflow must still be parsable YAML (we are reading it as a contract).
            try:
                yaml.safe_load(text)
            except yaml.YAMLError as exc:
                fail(f"{workflow.relative_to(ROOT)}: not parsable YAML ({exc})")

    # 2b. The task exists, is a JavaExec, and is not attached to check/build.
    build = read(E2E_BUILD)
    if not build:
        return
    if f"tasks.register('{TASK_NAME}', JavaExec)" not in build:
        fail(f"{E2E_BUILD.relative_to(ROOT)}: no `tasks.register('{TASK_NAME}', JavaExec)`. The harness "
             f"is deliberately a JavaExec main rather than a JUnit test precisely so no tag, no "
             f"`build` and no `check` can reach it.")
    if re.search(rf"dependsOn\s*[('\"\s]*{TASK_NAME}", build) or \
            re.search(rf"{TASK_NAME}[^\n]*\.dependsOn", build):
        fail(f"{E2E_BUILD.relative_to(ROOT)}: something dependsOn '{TASK_NAME}'. Nothing may pull the "
             f"load harness into another task's graph.")
    # The task must not build service jars: it does not start the fleet.
    task_block = re.search(rf"tasks\.register\('{TASK_NAME}'.*?\n\}}", build, re.S)
    if task_block and "bootJar" in task_block.group(0):
        fail(f"{E2E_BUILD.relative_to(ROOT)}: the '{TASK_NAME}' task depends on a bootJar. The harness "
             f"does NOT start the fleet -- building service jars implies it does, and would make a "
             f"cold-start compile look like payment latency.")

    # 2c. The harness entry point must not be a test.
    harness = read(HARNESS)
    if harness:
        if "@Test" in harness or "@Tag(" in harness:
            fail(f"{HARNESS.relative_to(ROOT)}: carries @Test/@Tag. A JUnit-discoverable load harness "
                 f"is one `--tests` glob away from running unattended.")
        if "public static void main(" not in harness:
            fail(f"{HARNESS.relative_to(ROOT)}: no main(String[]) -- the JavaExec task cannot reach it")
        # The interlock must run before any socket is opened, i.e. before the preflight.
        guard_at = harness.find("requireLocalDevTarget")
        preflight_at = harness.find("preflight(probeClient")
        if guard_at < 0:
            fail(f"{HARNESS.relative_to(ROOT)}: main() never calls "
                 f"LoadTargetGuard.requireLocalDevTarget -- the interlock is bypassed")
        elif 0 <= preflight_at < guard_at:
            fail(f"{HARNESS.relative_to(ROOT)}: the preflight probe runs BEFORE "
                 f"requireLocalDevTarget. Nothing may touch the network until the target is vetted.")


# ---------------------------------------------------------------------------
# 3. The guard's own tests run in the ordinary `test` task
# ---------------------------------------------------------------------------
def check_guard_is_tested() -> None:
    for path in (GUARD_TEST, TARGETS_TEST):
        src = read(path)
        if not src:
            continue
        if "@Tag(" in src:
            fail(f"{path.relative_to(ROOT)}: is tagged. The root build excludes 'docker' and 'e2e' from "
                 f"the default `test` task, so a tag here would stop CI from ever re-proving that the "
                 f"harness refuses a non-local target.")
        if "@Test" not in src:
            fail(f"{path.relative_to(ROOT)}: contains no @Test")
    guard_test = GUARD_TEST.read_text(encoding="utf-8") if GUARD_TEST.is_file() else ""
    if guard_test and "RefusedException" not in guard_test:
        fail(f"{GUARD_TEST.relative_to(ROOT)}: never asserts a refusal -- the one behaviour that "
             f"matters is untested")


# ---------------------------------------------------------------------------
# 4. Default target URLs are local
# ---------------------------------------------------------------------------
def check_default_urls_are_local() -> None:
    src = read(OPTIONS)
    if not src:
        return
    urls = re.findall(r'"(https?://[^"]+)"', src)
    for url in urls:
        host = re.sub(r"^https?://", "", url).split("/")[0].split(":")[0].lower()
        if host not in ("localhost", "127.0.0.1", "[::1]") and not host.endswith(".localhost"):
            fail(f"{OPTIONS.relative_to(ROOT)}: default URL '{url}' is not loopback. Defaults are what "
                 f"a hurried operator actually runs.")
    if not urls:
        notes.append(f"{OPTIONS.relative_to(ROOT)}: no default URL literals found (fine if they moved, "
                     f"but this guard can no longer check them)")


# ---------------------------------------------------------------------------
# 5. The shipped SLO file declares nothing
# ---------------------------------------------------------------------------
def check_slo_file_declares_nothing() -> None:
    if not SLO_FILE.is_file():
        fail(f"{SLO_FILE.relative_to(ROOT)} is missing -- an owner needs somewhere to declare SLOs, and "
             f"the harness reads it to decide PASS / FAIL / NO_TARGETS_DECLARED")
        return
    declared: list[str] = []
    for lineno, line in enumerate(SLO_FILE.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("!"):
            continue
        if "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        if value.strip():
            declared.append(f"line {lineno}: {key.strip()}={value.strip()}")
    if declared:
        fail(f"{SLO_FILE.relative_to(ROOT)}: SLO targets are declared:\n      "
             + "\n      ".join(declared)
             + "\n    If a named owner really declared these, update this guard and the T3-5 entry in "
               "Documentation/GAP_REGISTER.md in the same commit. If they are placeholder numbers, "
               "remove them: an invented target makes every run look green and is exactly what T3-5 "
               "warns against.")


# ---------------------------------------------------------------------------
# 6. The runbook exists and points at the register
# ---------------------------------------------------------------------------
def check_runbook() -> None:
    src = read(RUNBOOK)
    if not src:
        return
    for required in ("T3-5", ACK_FLAG, TASK_NAME, "SLO_TARGETS.properties"):
        if required not in src:
            fail(f"{RUNBOOK.relative_to(ROOT)}: does not mention '{required}' -- an operator reading "
                 f"only the runbook would miss it")
    if "NOT cover" not in src and "NOT covered" not in src:
        fail(f"{RUNBOOK.relative_to(ROOT)}: has no 'what this does NOT cover' section. Every runbook in "
             f"Documentation/ carries one; a load runbook without it invites an SLA promise.")


def main() -> int:
    check_guard()
    check_never_automatic()
    check_guard_is_tested()
    check_default_urls_are_local()
    check_slo_file_declares_nothing()
    check_runbook()

    for note in notes:
        print(f"NOTE: {note}")
    if failures:
        print(f"\nFAIL: {len(failures)} load-harness wiring problem(s):\n")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("OK: load harness is local-only, acknowledgement-gated, never automatic, and ships with no "
          "invented SLO targets.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
