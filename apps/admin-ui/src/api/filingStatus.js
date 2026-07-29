/**
 * filingStatus — the ONE place admin-UI decides how a regulatory filing status is
 * worded and coloured (GAP T5-2).
 *
 * Mirrors, by value, the vocabulary `services/reporting-compliance` can actually
 * produce and `ops-partner-bff`'s `compliance/FilingStatuses` passes through:
 *
 *   PENDING  →  GENERATED  →  VALIDATED  →  NOT_FILED_CHANNEL_UNAVAILABLE   (terminal today)
 *                                       ↘  TRANSMITTED → ACKNOWLEDGED       (UNREACHABLE today)
 *   FAILED    — the run itself failed
 *   UNKNOWN   — the service did not report a status; nothing may be assumed filed
 *
 * Two rules this module exists to enforce:
 *
 *  1. **Green means filed, and nothing else.** Only `TRANSMITTED` (bytes accepted by a
 *     real channel) and `ACKNOWLEDGED` (authority returned a receipt) get a success
 *     colour. `GENERATED`/`VALIDATED` are real local capabilities but they are not a
 *     filing — validation is against OUR OWN format checks, not an authority's.
 *     No regulatory lane has a live filing channel today, so in practice nothing in
 *     this app should be green.
 *  2. **The retired vocabulary can never look good.** `SUBMITTED`/`CONFIRMED`/
 *     `ACCEPTED`/`FILED` were fabricated by stubs and are now rejected by a DB CHECK
 *     constraint, so they cannot arrive from a current backend — but an old service or
 *     a cached response could still say them. They resolve to a warning-coloured
 *     "not filed / unrecognised" presentation rather than a green tick. This is not
 *     re-deriving backend truth: it is refusing to upgrade an unknown claim.
 */

export const FILING_STATUS = {
  PENDING: 'PENDING',
  GENERATED: 'GENERATED',
  VALIDATED: 'VALIDATED',
  NOT_FILED_CHANNEL_UNAVAILABLE: 'NOT_FILED_CHANNEL_UNAVAILABLE',
  TRANSMITTED: 'TRANSMITTED',
  ACKNOWLEDGED: 'ACKNOWLEDGED',
  FAILED: 'FAILED',
  UNKNOWN: 'UNKNOWN',
};

/** Statuses that were fabricated by the old stubs and must never render as success. */
export const RETIRED_FILING_STATUSES = ['SUBMITTED', 'CONFIRMED', 'ACCEPTED', 'FILED'];

const META = {
  [FILING_STATUS.PENDING]: {
    label: 'PENDING',
    color: 'default',
    filed: false,
    artifact: false,
    description: 'Filing row opened. Nothing generated and nothing filed.',
  },
  [FILING_STATUS.GENERATED]: {
    label: 'GENERATED (local)',
    color: 'info',
    filed: false,
    artifact: true,
    description:
      'Aggregated and the artifact was produced locally. NOT filed — no authority has seen it.',
  },
  [FILING_STATUS.VALIDATED]: {
    label: 'VALIDATED (local checks)',
    color: 'info',
    filed: false,
    artifact: true,
    description:
      'Passed our own format checks (non-empty, no TODO placeholders). This is not an authority '
      + 'confirmation and is not a filing.',
  },
  [FILING_STATUS.NOT_FILED_CHANNEL_UNAVAILABLE]: {
    label: 'NOT FILED — no channel',
    color: 'warning',
    filed: false,
    artifact: true,
    description:
      'Generated but never transmitted: the lane has no live filing channel. Terminal state for '
      + 'every regulatory lane today.',
  },
  [FILING_STATUS.TRANSMITTED]: {
    label: 'TRANSMITTED',
    color: 'success',
    filed: true,
    artifact: true,
    description: 'Bytes accepted by a real filing channel.',
  },
  [FILING_STATUS.ACKNOWLEDGED]: {
    label: 'ACKNOWLEDGED',
    color: 'success',
    filed: true,
    artifact: true,
    description: 'The authority returned a receipt for this filing.',
  },
  [FILING_STATUS.FAILED]: {
    label: 'FAILED',
    color: 'error',
    filed: false,
    artifact: false,
    description: 'The generation run itself failed.',
  },
  [FILING_STATUS.UNKNOWN]: {
    label: 'UNKNOWN — not verifiable',
    color: 'warning',
    filed: false,
    artifact: false,
    description:
      'The reporting service reported no filing status, so nothing about this run may be assumed '
      + 'filed.',
  },
};

/**
 * Presentation metadata for a wire status.
 *
 * @param {string|null|undefined} status
 * @returns {{ status: string, label: string, color: string, filed: boolean,
 *             artifact: boolean, description: string }}
 */
export function filingStatusMeta(status) {
  const raw = typeof status === 'string' ? status.trim().toUpperCase() : '';
  if (!raw) {
    return { status: FILING_STATUS.UNKNOWN, ...META[FILING_STATUS.UNKNOWN] };
  }
  if (META[raw]) {
    return { status: raw, ...META[raw] };
  }
  if (RETIRED_FILING_STATUSES.includes(raw)) {
    return {
      status: raw,
      label: `${raw} — not a valid state`,
      color: 'warning',
      filed: false,
      artifact: true,
      description:
        `"${raw}" is a retired status that no current backend can produce (it was fabricated by `
        + 'stubs and is now rejected at the database). Treat this run as NOT filed.',
    };
  }
  return {
    status: raw,
    label: `${raw} — unrecognised`,
    color: 'warning',
    filed: false,
    artifact: false,
    description:
      `"${raw}" is not a filing status this platform defines. Nothing may be assumed filed.`,
  };
}

/** True only when the status asserts a real filing reached an authority's channel. */
export function isFiled(status) {
  return filingStatusMeta(status).filed;
}

/** True when a locally generated artifact exists (i.e. a download can be offered). */
export function hasLocalArtifact(status) {
  return filingStatusMeta(status).artifact;
}

/**
 * One line an operator/auditor can read at a glance, given the rows on screen.
 * Returns null when at least one row claims a real filing (then the per-row chips
 * carry the story and a blanket banner would be wrong).
 *
 * @param {Array<{ status?: string }>} rows
 * @returns {string|null}
 */
export function notFiledSummary(rows) {
  const list = Array.isArray(rows) ? rows : [];
  if (list.length === 0) return null;
  if (list.some((r) => isFiled(r?.status))) return null;
  return 'None of these runs has been filed with an authority. Reports are generated and '
    + 'validated locally only — no regulatory lane has a live filing channel.';
}
