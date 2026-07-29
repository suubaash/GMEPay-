"""Self-test for /.gitleaks.toml — gap T5-5. Run from the repo root:

    python scripts/check_gitleaks_config.py       # exit 0 = config is sane

WHY THIS EXISTS. The blocking `secret-scan` job in .github/workflows/ci.yml is
only as good as its allowlist, and the failure mode of an allowlist is SILENT:
widen one entry and the gate keeps passing while no longer detecting anything.
This script is the regression test for that. It has three parts:

  1. every regex in the config compiles, and none uses a construct Go's RE2
     rejects (lookahead / lookbehind / backreferences) — a bad regex makes
     gitleaks fail to load the config, which is not the same as a clean scan;
  2. NEGATIVE CONTROL — 15 planted, realistic credential shapes that MUST still
     be reported. This is the check that catches an over-broad allowlist. Two
     real over-allows were found this way while writing the config (a
     line-scoped `GMEPAY_*` name pattern that silenced the value beside it, and
     a camelCase exemption that also matched base64);
  3. CORPUS — every git-tracked file, which should be clean, plus a list of
     known-benign lines that must stay quiet.

IF YOU WIDEN THE ALLOWLIST, RUN THIS. If a real secret moves from CAUGHT to
MISSED, the allowlist entry is wrong — narrow it. Never delete a MUST_CATCH case
to get to green.

NOTE ON FIDELITY: this re-implements gitleaks' matching (it does not shell out to
the binary, which is not available on the Windows build box). It reads the real
config, applies the real custom rules, and re-implements the highest-signal
default rules. It has NO stopword list, so it over-reports relative to real
gitleaks — a clean result here is a strong signal, not a proof. The authoritative
run is the CI job.
"""
import io
import math
import re
import subprocess
import sys
import tomllib

cfg = tomllib.load(open('.gitleaks.toml', 'rb'))
allow_line = [re.compile(x) for x in cfg['allowlist']['regexes']]
allow_path = [re.compile(x) for x in cfg['allowlist']['paths']]

# ---- part 1: RE2 compatibility ---------------------------------------------
# gitleaks compiles rules with Go's RE2. Lookahead, lookbehind and
# backreferences are NOT supported there; a config using them fails to LOAD,
# which shows up as a job error rather than a clean pass.
RE2_UNSUPPORTED = re.compile(r'\(\?=|\(\?!|\(\?<=|\(\?<!|\\[1-9]')
_all_patterns = [('allowlist.regexes', x) for x in cfg['allowlist']['regexes']]
_all_patterns += [('allowlist.paths', x) for x in cfg['allowlist']['paths']]
for _r in cfg['rules']:
    _all_patterns.append((_r['id'] + '.regex', _r['regex']))
    for _x in _r.get('allowlist', {}).get('regexes', []):
        _all_patterns.append((_r['id'] + '.allowlist', _x))
_re2_bad = [(n, p) for n, p in _all_patterns if RE2_UNSUPPORTED.search(p)]
print('== part 1: %d regexes compile, RE2-incompatible: %s =='
      % (len(_all_patterns), _re2_bad or 'none'))

RULES = []
for r in cfg['rules']:
    per = [re.compile(x) for x in r.get('allowlist', {}).get('regexes', [])]
    RULES.append((r['id'], re.compile(r['regex']), per, r.get('entropy')))

# gitleaks default rules this repo is realistically exposed to.
RULES += [
    ('private-key', re.compile(r'-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY( BLOCK)?-----'), [], None),
    ('aws-access-token', re.compile(r'\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16})\b'), [], None),
    ('github-pat', re.compile(r'\bgh[pousr]_[0-9a-zA-Z]{36}\b'), [], None),
    ('slack-token', re.compile(r'\bxox[baprs]-[0-9a-zA-Z-]{10,}'), [], None),
    ('jwt', re.compile(r'\bey[A-Za-z0-9_-]{17,}\.ey[A-Za-z0-9_-]{17,}\.[A-Za-z0-9_-]{10,}'), [], None),
]


def shannon(s):
    if not s:
        return 0.0
    return -sum((n / len(s)) * math.log2(n / len(s))
                for n in (s.count(c) for c in set(s)))


def scan_line(line):
    """Return list of (rule_id, secret) findings for one line, post-allowlist."""
    if len(line) > 4000 or any(a.search(line) for a in allow_line):
        return []
    out = []
    for rid, rx, per_rule, entropy in RULES:
        m = rx.search(line)
        if not m:
            continue
        g = m.groups()
        sec = g[0] if (g and g[0]) else m.group(0)
        if entropy and shannon(sec) < entropy:
            continue
        if any(a.search(sec) for a in per_rule):
            continue
        out.append((rid, sec))
    return out


# ---------------------------------------------------------------- corpus scan
files = subprocess.run(['git', 'ls-files'], capture_output=True, text=True,
                       check=True).stdout.splitlines()
findings, skipped = [], 0
for f in files:
    if any(p.search(f) for p in allow_path):
        skipped += 1
        continue
    try:
        text = io.open(f, encoding='utf-8', errors='replace').read()
    except Exception:
        continue
    for lineno, line in enumerate(text.splitlines(), 1):
        for rid, sec in scan_line(line):
            findings.append((rid, f, lineno, sec[:14] + '...'))

print('== corpus (git-tracked working tree) ==')
print('scanned %d files, %d allowlisted paths skipped' % (len(files) - skipped, skipped))
print('findings: %d' % len(findings))
for x in findings[:40]:
    print('  [%s] %s:%d  %s' % x)

# ------------------------------------------------------------ negative control
MUST_CATCH = [
    'GMEPAY_INTERNAL_AUTH_SECRET: 8f3a91c04be7d25a6019fbc8734ee1a2',
    'SPRING_DATASOURCE_PASSWORD: Tr0ub4dor-Horse-Battery-9912',
    'gmepay.partner.apiKey=pk_live_9f2c81a4bd0e47c6a51b93df2e7c',
    'apiSecret: sk_live_4b19e7c0d83a615fbe27904cd1a8f36e5729bd04',
    'signingSecret = "whsec_Zm9vYmFyYmF6cXV1eGNvcmdlZ3JhdWx0"',
    'AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLZ',
    'token: ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8',
    'SPRING_DATASOURCE_URL: jdbc:postgresql://gmeadmin:Sup3rS3cretPg@db:5432/gmepay',
    '-----BEGIN RSA PRIVATE KEY-----',
    'this.apiSecret = "sk_live_4b19e7c0d83a615fbe27904cd1a8f36e5729bd04";',
    'restClient = "sk_live_4b19e7c0d83a615fbe27904cd1a8f36e5729bd04";',
    'password: hunter2passwordthatisreal',
    'X-Gme-Internal: 6d1f0b9c74ae2385fd10cb4790aa2f18',
    '  GMEPAY_RBAC_SECRET: 0b9c74ae2385fd10cb4790aa2f186d1f   # rotated 2026-07',
    'static final String API_KEY = "aG91c2Vib2F0Q2FybmV5MTk4N1p6";',
]
MUST_STAY_QUIET = [
    'x-internal-auth-secret: &internal-auth-secret ${GMEPAY_INTERNAL_AUTH_SECRET:-dev-internal-svc-secret-not-for-prod}',
    '    GMEPAY_RBAC_SECRET: CHANGE_ME_RBAC_EDGE_SECRET',
    '    SPRING_DATASOURCE_PASSWORD: REPLACE_FROM_SECRETS_MANAGER',
    'RUN keytool -importcert -noprompt -trustcacerts -alias gme-root -file x.crt -cacerts -storepass changeit',
    '        this.authorizationService = authorizationService;',
    "export const EXPIRES_AT_KEY = 'gmepay.adminTokenExpiresAt';",
    'static final String NOT_FOUND_TOKEN = "BIZREG_NOTFOUND";',
    'static final String KEY_TIER_95 = "prefunding.alert.tier1.pct";',
    "const KEY_PLACEHOLDER = 'sk_test_YOUR_SANDBOX_KEY';",
    '# REFUSES TO START on a blank secret. Callers: payment-executor + settlement-reconciliation.',
    ' *       ({@code -----BEGIN PRIVATE KEY-----}) or bare base64 DER.',
    'private static final char[] TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();',
]

print()
print('== negative control: MUST be caught ==')
missed = []
for line in MUST_CATCH:
    hits = scan_line(line)
    print('%-8s %s' % ('CAUGHT' if hits else 'MISSED', line[:92]))
    if not hits:
        missed.append(line)

print()
print('== control: MUST stay quiet (known-benign) ==')
noisy = []
for line in MUST_STAY_QUIET:
    hits = scan_line(line)
    print('%-8s %s' % ('quiet' if not hits else 'NOISY', line.strip()[:92]))
    if hits:
        noisy.append((line, hits))

print()
print('corpus findings=%d  missed-real-secrets=%d  false-positives=%d  re2-bad=%d'
      % (len(findings), len(missed), len(noisy), len(_re2_bad)))
if missed:
    print('FAIL: the allowlist now hides a real credential shape. NARROW the entry')
    print('      that silenced it; do not delete the MUST_CATCH case.')
if noisy or findings:
    print('FAIL: new findings. Triage them as real secrets FIRST. Only add an')
    print('      allowlist entry if the value is provably a placeholder/fixture.')
sys.exit(1 if (missed or noisy or findings or _re2_bad) else 0)
