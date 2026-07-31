/**
 * truth.js — the generator's source of truth.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The exec-facing feature specification used to *restate* what the platform does. Restated
 * claims drift: the document said "large/suspicious transactions are detected and filed
 * (CTR/STR)" for weeks while there was no transaction screening in the payment path and no
 * filing channel to any authority (gaps T5-3 / T5-11 / T5-2). Nobody edited the document to
 * make it false — it simply stopped being true and had no way to notice.
 *
 * So the document no longer restates. It DERIVES:
 *
 *   1. `gaps()` parses `Documentation/GAP_REGISTER.md` — the register is the branch's SSOT
 *      for what is built, gated, or absent — and exposes each gap's status marker.
 *   2. `assertGap(id, state)` pins every honest qualifier in the document to the register
 *      marker that justifies it. If a gap CLOSES, the generator FAILS LOUDLY rather than
 *      quietly keeping a stale pessimistic sentence; if a gap re-opens, likewise. Either way
 *      an author has to look at the claim. A document that can silently drift back into
 *      overstatement is the bug; this is the interlock against it.
 *   3. `token()` reads the platform's own honest status vocabularies out of the source that
 *      defines them (`SettlementTransmissionState`, `FilingChannelStatus`, `ScreeningProvenance`,
 *      admin-ui's `filingStatus.js` / `settlementStatus.js`) and asserts the value the document
 *      prints still exists. The document quotes the platform's words, not its own.
 *
 * NOTHING HERE READS A DATABASE OR CALLS A SERVICE. It reads committed files only, so the
 * document is reproducible offline and byte-deterministic.
 */

'use strict';

const fs = require('fs');
const path = require('path');

/** Repo root — this file lives at <root>/outputs/docgen/truth.js. */
const ROOT = path.resolve(__dirname, '..', '..');

const REGISTER = path.join(ROOT, 'Documentation', 'GAP_REGISTER.md');

// ---------------------------------------------------------------------------
// 1. The gap register
// ---------------------------------------------------------------------------

/** Register marker → the state word this generator uses. */
const MARKERS = {
  ' ': 'OPEN',      // nothing built
  '~': 'PARTIAL',   // part built, remainder named
  x: 'CLOSED',      // done
  D: 'DECISION',    // blocked on a named owner's decision
};

let _gaps = null;

/**
 * Every gap in the register, by id.
 *
 * Only the checkbox line is parsed (`- [~] **T5-2. ...**`). The register's bodies are long
 * essays and deliberately not machine-read: the marker is the machine-readable part, which is
 * exactly why the register keeps one.
 *
 * @returns {Map<string, {id: string, marker: string, state: string, title: string, line: number}>}
 */
function gaps() {
  if (_gaps) return _gaps;
  const text = fs.readFileSync(REGISTER, 'utf8');
  const out = new Map();
  text.split(/\r?\n/).forEach((raw, i) => {
    const m = /^- \[([ ~xD])\] \*\*([A-Z]+\d+-\d+[a-z]?)\.\s*([\s\S]*?)\*\*/.exec(raw);
    if (!m) return;
    const [, marker, id, title] = m;
    if (out.has(id)) {
      throw new Error(`GAP_REGISTER.md declares ${id} twice (line ${i + 1}) — cannot derive truth from an ambiguous register`);
    }
    out.set(id, {
      id,
      marker,
      state: MARKERS[marker],
      title: title.replace(/\s+/g, ' ').trim(),
      line: i + 1,
    });
  });
  if (out.size < 30) {
    throw new Error(`only ${out.size} gaps parsed from GAP_REGISTER.md — the format changed; fix the parser before regenerating`);
  }
  _gaps = out;
  return out;
}

/**
 * Assert a gap is in the state a document claim depends on, and return it.
 *
 * This is the anti-drift interlock. Every qualified sentence in the specification names the
 * gap it is qualified by; if the register moves, the build breaks here with a message naming
 * the sentence's justification, so the sentence gets revisited by a human.
 *
 * @param {string} id       register gap id, e.g. 'T5-2'
 * @param {string|string[]} expected one or more acceptable states
 * @returns {{id: string, state: string, title: string}}
 */
function assertGap(id, expected) {
  const g = gaps().get(id);
  if (!g) {
    throw new Error(`the specification cites gap ${id}, which is not in GAP_REGISTER.md`);
  }
  const want = Array.isArray(expected) ? expected : [expected];
  if (!want.includes(g.state)) {
    throw new Error(
      `DRIFT: gap ${id} is now ${g.state} in GAP_REGISTER.md (line ${g.line}) but the `
      + `specification's wording assumes ${want.join(' or ')}.\n`
      + `  ${id}: ${g.title.slice(0, 140)}\n`
      + '  Re-read the claim that cites it in content.js and correct the WORDING, then update this assertion. '
      + 'Do not relax the assertion to make the build pass.',
    );
  }
  return g;
}

// ---------------------------------------------------------------------------
// 2. The platform's own honest status vocabularies
// ---------------------------------------------------------------------------

const VOCAB_FILES = {
  settlementTransmission:
    'services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/transmission/SettlementTransmissionState.java',
  settlementChannel:
    'services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/transmission/SettlementTransmissionChannelStatus.java',
  filingChannel:
    'services/reporting-compliance/src/main/java/com/gme/pay/reporting/channel/FilingChannelStatus.java',
  screeningProvenance: 'libs/lib-kyb/src/main/java/com/gme/pay/kyb/ScreeningProvenance.java',
  adminFilingStatus: 'apps/admin-ui/src/api/filingStatus.js',
  adminSettlementStatus: 'apps/admin-ui/src/api/settlementStatus.js',
  sloTargets: 'Documentation/SLO_TARGETS.properties',
};

/**
 * Assert a literal token still exists in one of the platform's vocabulary files, and return it.
 *
 * The point is not the string — it is that the document cannot print a status word the platform
 * has stopped defining, or keep printing one after it is renamed.
 *
 * @param {keyof VOCAB_FILES} which
 * @param {string} literal
 * @returns {string} the literal
 */
function token(which, literal) {
  const rel = VOCAB_FILES[which];
  if (!rel) throw new Error(`unknown vocabulary source '${which}'`);
  const abs = path.join(ROOT, rel);
  let body;
  try {
    body = fs.readFileSync(abs, 'utf8');
  } catch (e) {
    throw new Error(`vocabulary source ${rel} is unreadable (${e.code}) — the specification quotes it, so it may not be moved silently`);
  }
  if (!body.includes(literal)) {
    throw new Error(
      `DRIFT: '${literal}' no longer appears in ${rel}. The specification prints this as the `
      + 'platform\'s own status word. Find what replaced it and update the document.',
    );
  }
  return literal;
}

/**
 * True when `Documentation/SLO_TARGETS.properties` still declares nothing — i.e. the platform
 * has no service-level objective to be measured against. Derived, because the whole point of
 * that file is that it ships blank and the honest sentence must follow it.
 *
 * @returns {boolean}
 */
function noSloTargetsDeclared() {
  const abs = path.join(ROOT, VOCAB_FILES.sloTargets);
  const body = fs.readFileSync(abs, 'utf8');
  return !body
    .split(/\r?\n/)
    .some((l) => /^[^#\s][^=]*=\s*\S/.test(l));
}

// ---------------------------------------------------------------------------
// 3. States a capability may be in, in this document
// ---------------------------------------------------------------------------

/**
 * The document's capability vocabulary, deliberately shaped like admin-ui's `filingStatus.js`
 * and `settlementStatus.js`: ONE place decides the wording, and the success value is reserved.
 *
 * Rule, mirroring "green means filed, and nothing else": **LIVE means a real external
 * counterparty has exercised it, and nothing else.** `assertNothingClaimsLive()` enforces that
 * the value is unreachable while T1-6 (no always-on environment) is open, so no edit to this
 * document can promote a capability to LIVE without the register moving first.
 */
const STATE = {
  LIVE: {
    key: 'LIVE',
    label: 'LIVE',
    tone: 'ok',
    gloss: 'exercised against the real external counterparty in a standing environment',
  },
  LOCAL: {
    key: 'LOCAL',
    label: 'BUILT · LOCAL ONLY',
    tone: 'info',
    gloss: 'built, and exercised only against local simulators and automated tests — never against the real counterparty',
  },
  GATED: {
    key: 'GATED',
    label: 'BUILT · NOT LIVE',
    tone: 'warn',
    gloss: 'built and refusing on purpose: it cannot act until a named external gate opens',
  },
  PARTIAL: {
    key: 'PARTIAL',
    label: 'PARTLY BUILT',
    tone: 'warn',
    gloss: 'some of it exists; what is missing is named rather than implied',
  },
  ABSENT: {
    key: 'ABSENT',
    label: 'NOT BUILT',
    tone: 'bad',
    gloss: 'does not exist — there is no code for it',
  },
  DECISION: {
    key: 'DECISION',
    label: 'BLOCKED ON A DECISION',
    tone: 'bad',
    gloss: 'deliberately unbuilt until a named owner decides; inventing the answer would be worse than the gap',
  },
};

/**
 * Structural interlock: nothing in this document may be presented as LIVE while the register
 * says there is no always-on environment for anything to be live in (T1-6).
 *
 * @param {Array<{state?: {key: string}}>} rows every status-bearing row in the document
 */
function assertNothingClaimsLive(rows) {
  const env = gaps().get('T1-6');
  const noEnvironment = env && env.state !== 'CLOSED';
  const claimed = rows.filter((r) => r.state && r.state.key === 'LIVE');
  if (noEnvironment && claimed.length > 0) {
    throw new Error(
      `${claimed.length} capabilit(y/ies) claim LIVE, but T1-6 (no always-on environment) is `
      + `${env.state} in GAP_REGISTER.md — nothing can be live where nothing is running. `
      + `Offending: ${claimed.map((r) => r.capability || r.term).join(', ')}`,
    );
  }
}

module.exports = {
  ROOT,
  gaps,
  assertGap,
  token,
  noSloTargetsDeclared,
  STATE,
  assertNothingClaimsLive,
};
