/**
 * settlementStatus — the ONE place admin-UI decides how a settlement batch's
 * LIFECYCLE status and its TRANSMISSION state are worded and coloured (GAP T4-5).
 *
 * Deliberately the same shape as `@/api/filingStatus` (T5-2) so an operator reads one
 * vocabulary across the regulatory-filing lane and the settlement lane. Do not add a
 * third pattern.
 *
 * <h2>Why two axes and not one</h2>
 * `settlement_batches.status` answers **how far reconciliation got**. It was also being
 * asked "did we send it?", and those are independent facts: a batch can be `RECONCILED`
 * — a confirmation file genuinely arrived and genuinely tied out against the persisted
 * lines — while GME never transmitted the request file, because today the file is picked
 * out of a **local directory** by hand and a local directory is not a channel. So
 * settlement-reconciliation carries `transmission_state` as its own column (V013) with
 * its own vocabulary, and `ops-partner-bff`'s `SettlementStatuses` passes both through.
 *
 *   lifecycle:     PENDING → GENERATED → (TRANSMITTED) → RECEIVED → RECONCILED | ERROR
 *   transmission:  NOT_TRANSMITTED | NOT_TRANSMITTED_CHANNEL_UNAVAILABLE
 *                  | TRANSMISSION_FAILED | TRANSMITTED
 *
 * <h2>Three rules this module exists to enforce</h2>
 *  1. **Green means transmitted, and nothing else.** Only the TRANSMISSION axis can ever
 *     be a success, and only on `TRANSMITTED`. No lifecycle value gets a success colour —
 *     not even `RECONCILED`, and not even the lifecycle literal `TRANSMITTED`, because the
 *     lifecycle column is not the platform's answer to "did the file leave?" (it was
 *     historically fast-forwarded through `TRANSMITTED` as pure bookkeeping; V013
 *     reclassified those rows on the new column only and deliberately did NOT rewrite
 *     `status`). A lifecycle chip must never read as a lone green tick.
 *  2. **Absent or unrecognised is UNKNOWN, never promoted.** "The service did not tell
 *     us" and "the service told us it was not sent" are different facts and only the
 *     second may be reported. Neither is ever `TRANSMITTED`. This is not re-deriving
 *     backend truth — it is refusing to upgrade an unknown claim, so a stale service or a
 *     cached response cannot paint a green tick.
 *  3. **The retired invented vocabulary can never look good.** `COMPLETED` was literally
 *     hardcoded by the BFF onto rows that corresponded to no persisted batch; `SETTLED` /
 *     `PAID` / `SUCCESS` / `DONE` / `FINAL` are the plausible neighbours a later change
 *     might reach for. No upstream has ever produced any of them, but an old service or a
 *     cached response could still say them, so they resolve to a warning-coloured
 *     "not a valid state" presentation.
 */

/** Real lifecycle vocabulary — `SettlementBatchStatus` in settlement-reconciliation. */
export const SETTLEMENT_LIFECYCLE = {
  PENDING: 'PENDING',
  GENERATED: 'GENERATED',
  TRANSMITTED: 'TRANSMITTED',
  RECEIVED: 'RECEIVED',
  RECONCILED: 'RECONCILED',
  ERROR: 'ERROR',
  UNKNOWN: 'UNKNOWN',
};

/** Real transmission vocabulary — `SettlementTransmissionState` (V013). */
export const TRANSMISSION_STATE = {
  NOT_TRANSMITTED: 'NOT_TRANSMITTED',
  NOT_TRANSMITTED_CHANNEL_UNAVAILABLE: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
  TRANSMISSION_FAILED: 'TRANSMISSION_FAILED',
  TRANSMITTED: 'TRANSMITTED',
  UNKNOWN: 'UNKNOWN',
};

/**
 * Statuses that were invented downstream and must never render as success.
 * `COMPLETED` is the literal the BFF used to hardcode on every settlement row.
 */
export const RETIRED_SETTLEMENT_STATUSES = [
  'COMPLETED', 'SETTLED', 'PAID', 'SUCCESS', 'DONE', 'FINAL',
];

const LIFECYCLE_META = {
  [SETTLEMENT_LIFECYCLE.PENDING]: {
    label: 'PENDING',
    color: 'default',
    description: 'Batch row opened. Nothing generated.',
  },
  [SETTLEMENT_LIFECYCLE.GENERATED]: {
    label: 'GENERATED (local)',
    color: 'info',
    description:
      'The settlement file was produced locally from the persisted lines. This says nothing about '
      + 'whether it was sent — see the transmission state.',
  },
  [SETTLEMENT_LIFECYCLE.TRANSMITTED]: {
    label: 'TRANSMITTED (lifecycle only)',
    color: 'warning',
    description:
      'A legacy lifecycle value. Reconciliation used to fast-forward every batch through this '
      + 'status as bookkeeping, so it is NOT evidence that a file left the platform. Read the '
      + 'transmission state instead — that is the only column that answers "did we send it?".',
  },
  [SETTLEMENT_LIFECYCLE.RECEIVED]: {
    label: 'RECEIVED',
    color: 'info',
    description:
      'A confirmation file from the scheme was ingested. Inbound only — it implies nothing about '
      + 'our own outbound transmission.',
  },
  [SETTLEMENT_LIFECYCLE.RECONCILED]: {
    label: 'RECONCILED',
    color: 'info',
    description:
      'The scheme confirmation tied out against the persisted settlement lines. This is a real '
      + 'achievement and it is NOT a transmission: a batch can be RECONCILED while GME never sent '
      + 'the request file.',
  },
  [SETTLEMENT_LIFECYCLE.ERROR]: {
    label: 'ERROR',
    color: 'error',
    description: 'The reconciliation run for this batch failed.',
  },
  [SETTLEMENT_LIFECYCLE.UNKNOWN]: {
    label: 'UNKNOWN — not verifiable',
    color: 'warning',
    description:
      'Settlement-reconciliation reported no lifecycle status, so nothing about this batch may be '
      + 'assumed reconciled or sent.',
  },
};

const TRANSMISSION_META = {
  [TRANSMISSION_STATE.NOT_TRANSMITTED]: {
    label: 'Not sent',
    shortLabel: 'No',
    color: 'warning',
    transmitted: false,
    description:
      'No settlement file has been transmitted to the scheme for this batch. A channel exists but '
      + 'nothing has been sent yet.',
  },
  [TRANSMISSION_STATE.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE]: {
    label: 'Not sent — no channel',
    shortLabel: 'No',
    color: 'warning',
    transmitted: false,
    description:
      'Generated but never transmitted: this deployment has no transmission channel to the scheme. '
      + 'A local directory is not a channel. Terminal state for every settlement batch today.',
  },
  [TRANSMISSION_STATE.TRANSMISSION_FAILED]: {
    label: 'Send FAILED',
    shortLabel: 'Failed',
    color: 'error',
    transmitted: false,
    description:
      'A live channel was attempted and rejected the file. Nothing reached the scheme.',
  },
  [TRANSMISSION_STATE.TRANSMITTED]: {
    label: 'Sent to scheme',
    shortLabel: 'Yes',
    color: 'success',
    transmitted: true,
    description: 'The settlement file was accepted by a real channel to the scheme.',
  },
  [TRANSMISSION_STATE.UNKNOWN]: {
    label: 'Unknown — not verifiable',
    shortLabel: 'Unknown',
    color: 'warning',
    transmitted: false,
    description:
      'Settlement-reconciliation reported no transmission state for this batch, so nothing may be '
      + 'assumed sent. An absent value is never a success.',
  },
};

/**
 * Presentation metadata for a batch LIFECYCLE status. Never returns a success colour —
 * see rule 1.
 *
 * @param {string|null|undefined} status
 * @returns {{ status: string, label: string, color: string, description: string }}
 */
export function settlementLifecycleMeta(status) {
  const raw = typeof status === 'string' ? status.trim().toUpperCase() : '';
  if (!raw) {
    return { status: SETTLEMENT_LIFECYCLE.UNKNOWN, ...LIFECYCLE_META[SETTLEMENT_LIFECYCLE.UNKNOWN] };
  }
  if (LIFECYCLE_META[raw]) {
    return { status: raw, ...LIFECYCLE_META[raw] };
  }
  if (RETIRED_SETTLEMENT_STATUSES.includes(raw)) {
    return {
      status: raw,
      label: `${raw} — not a valid state`,
      color: 'warning',
      description:
        `"${raw}" is not in settlement-reconciliation's own lifecycle vocabulary — it could only `
        + 'have been invented downstream (the BFF used to hardcode "COMPLETED" on every row). '
        + 'Treat this batch as neither reconciled nor sent.',
    };
  }
  return {
    status: raw,
    label: `${raw} — unrecognised`,
    color: 'warning',
    description:
      `"${raw}" is not a settlement lifecycle status this platform defines. Nothing may be assumed.`,
  };
}

/**
 * Presentation metadata for a TRANSMISSION state. The only place a success colour can
 * come from, and only on `TRANSMITTED`.
 *
 * @param {string|null|undefined} state
 * @returns {{ state: string, label: string, shortLabel: string, color: string,
 *             transmitted: boolean, description: string }}
 */
export function transmissionStateMeta(state) {
  const raw = typeof state === 'string' ? state.trim().toUpperCase() : '';
  if (!raw) {
    return { state: TRANSMISSION_STATE.UNKNOWN, ...TRANSMISSION_META[TRANSMISSION_STATE.UNKNOWN] };
  }
  if (TRANSMISSION_META[raw]) {
    return { state: raw, ...TRANSMISSION_META[raw] };
  }
  // Anything else — including a lifecycle word that leaked into this field, and including
  // the retired invented vocabulary — is UNKNOWN. It is never promoted to a success.
  return {
    state: raw,
    label: `${raw} — unrecognised`,
    shortLabel: 'Unknown',
    color: 'warning',
    transmitted: false,
    description:
      `"${raw}" is not a transmission state this platform defines. An uninterpretable value never `
      + 'means sent.',
  };
}

/** True only when the backend said, in its own vocabulary, that the file was sent. */
export function isTransmitted(state) {
  return transmissionStateMeta(state).transmitted;
}

/**
 * One line an operator/auditor can read at a glance, given the rows on screen.
 * Returns null when at least one row claims a real transmission (then the per-row
 * chips carry the story and a blanket banner would be wrong), and null for an empty
 * list (there is nothing to make a claim about).
 *
 * @param {Array<{ transmissionState?: string }>} rows
 * @returns {string|null}
 */
export function notTransmittedSummary(rows) {
  const list = Array.isArray(rows) ? rows : [];
  if (list.length === 0) return null;
  if (list.some((r) => isTransmitted(r?.transmissionState))) return null;
  return 'No settlement file on this page has been transmitted to a scheme. Batches are '
    + 'generated and reconciled against the scheme’s confirmation locally — a lifecycle '
    + 'status such as RECONCILED does not mean anything was sent.';
}

/**
 * The reason to show for a row, preferring what the backend said over our own wording so
 * an operator is never left guessing why a row says UNKNOWN.
 *
 * @param {{ transmissionState?: string, transmissionReason?: string }} row
 * @returns {string|null}
 */
export function transmissionReasonFor(row) {
  const upstream = row?.transmissionReason;
  if (typeof upstream === 'string' && upstream.trim()) return upstream.trim();
  return transmissionStateMeta(row?.transmissionState).description;
}
