/**
 * screeningStatus — the ONE place admin-UI decides how a KYB sanctions/PEP screening verdict is
 * worded and coloured (GAP T1-4).
 *
 * Mirrors, by value, the vocabulary `libs/lib-kyb`'s `ScreeningResult.Status` can produce and the
 * `partner_kyb.screening_status` CHECK (config-registry V045) can store:
 *
 *   CLEAR                     — a real VENDOR screening found no matches
 *   CLEAR_MANUAL_ATTESTATION  — a named human screened by hand under a compliance-signed SOP
 *   NEEDS_REVIEW              — fuzzy/partial matches an analyst must disposition
 *   HIT                       — a confident list match
 *   NOT_SCREENED_NO_PROVIDER  — NOTHING was screened; the honest terminal state of a stub run
 *   (null/absent)             — no screening has been recorded at all
 *
 * Three rules this module exists to enforce:
 *
 *  1. **"Clear" never appears without saying who cleared it.** `CLEAR` and
 *     `CLEAR_MANUAL_ATTESTATION` both mean "no matches" and both satisfy activation, but they are
 *     different controls: one is a vendor against automated list feeds (with ongoing rescreening),
 *     the other is a person following a written procedure at one point in time. They get different
 *     labels and different chip variants so an operator can never read one as the other. This is
 *     the whole reason the backend keeps them as separate status values.
 *  2. **`NOT_SCREENED_NO_PROVIDER` is not "pending" and is not neutral.** It is the outcome of a
 *     completed run that consulted no list, so waiting changes nothing. It renders as a warning
 *     with an explicit "nothing was screened" label — never as a grey unknown, which reads as
 *     "not yet".
 *  3. **An unrecognised value is never upgraded.** Same rule as `filingStatus.js`: a status this
 *     app does not define resolves to a warning, not to success.
 */

export const SCREENING_STATUS = {
  CLEAR: 'CLEAR',
  CLEAR_MANUAL_ATTESTATION: 'CLEAR_MANUAL_ATTESTATION',
  NEEDS_REVIEW: 'NEEDS_REVIEW',
  HIT: 'HIT',
  NOT_SCREENED_NO_PROVIDER: 'NOT_SCREENED_NO_PROVIDER',
};

const META = {
  [SCREENING_STATUS.CLEAR]: {
    label: 'Clear — vendor screening',
    color: 'success',
    variant: 'filled',
    screened: true,
    clear: true,
    manual: false,
    activates: true,
    description:
      'A real screening provider consulted sanctions / PEP / adverse-media sources and found no '
      + 'matches.',
  },
  [SCREENING_STATUS.CLEAR_MANUAL_ATTESTATION]: {
    label: 'Clear — manual SOP attestation',
    color: 'info',
    variant: 'outlined',
    screened: true,
    clear: true,
    manual: true,
    activates: true,
    description:
      'A named compliance officer performed this screening BY HAND under a compliance-signed SOP '
      + 'and is accountable for it. It satisfies the activation pre-condition. It is NOT a vendor '
      + 'screening: no automated list feed was consulted and there is no ongoing rescreening as '
      + 'lists change.',
  },
  [SCREENING_STATUS.NEEDS_REVIEW]: {
    label: 'Needs review',
    color: 'warning',
    variant: 'filled',
    screened: true,
    clear: false,
    manual: false,
    activates: false,
    description:
      'Fuzzy or partial matches an analyst must disposition. Activation needs a documented risk '
      + 'rationale.',
  },
  [SCREENING_STATUS.HIT]: {
    label: 'Hit',
    color: 'error',
    variant: 'filled',
    screened: true,
    clear: false,
    manual: false,
    activates: false,
    description:
      'At least one confident list match. Compliance must review before any activation.',
  },
  [SCREENING_STATUS.NOT_SCREENED_NO_PROVIDER]: {
    label: 'NOT SCREENED — nothing was checked',
    color: 'warning',
    variant: 'outlined',
    screened: false,
    clear: false,
    manual: false,
    activates: false,
    description:
      'No authoritative provider produced this result — no sanctions, PEP or adverse-media source '
      + 'was consulted. This is NOT a clean result and activation is refused. Either configure a '
      + 'real KYB provider, or record a manual screening attestation under the compliance SOP.',
  },
};

const NOT_RECORDED = {
  status: null,
  label: 'Not screened yet',
  color: 'default',
  variant: 'outlined',
  screened: false,
  clear: false,
  manual: false,
  activates: false,
  description: 'No sanctions screening has been recorded for this partner.',
};

/**
 * Presentation metadata for a wire screening status.
 *
 * @param {string|null|undefined} status
 * @returns {{ status: string|null, label: string, color: string, variant: string,
 *             screened: boolean, clear: boolean, manual: boolean, activates: boolean,
 *             description: string }}
 */
export function screeningStatusMeta(status) {
  const raw = typeof status === 'string' ? status.trim().toUpperCase() : '';
  if (!raw) return { ...NOT_RECORDED };
  if (META[raw]) return { status: raw, ...META[raw] };
  return {
    status: raw,
    label: `${raw} — unrecognised`,
    color: 'warning',
    variant: 'outlined',
    screened: false,
    clear: false,
    manual: false,
    activates: false,
    description:
      `"${raw}" is not a screening status this platform defines. Nothing may be assumed screened.`,
  };
}

/** True only when a real authority (vendor OR attested human) performed a screening. */
export function wasScreened(status) {
  return screeningStatusMeta(status).screened;
}

/** True only when the clean verdict came from an attested MANUAL screening rather than a vendor. */
export function isManuallyAttested(status) {
  return screeningStatusMeta(status).manual;
}

/**
 * One line an operator can act on, given the status on screen. Returns null when a real vendor
 * screening is in place and there is nothing to caveat.
 *
 * @param {string|null|undefined} status
 * @returns {string|null}
 */
export function screeningCaveatSummary(status) {
  const meta = screeningStatusMeta(status);
  if (meta.status === SCREENING_STATUS.CLEAR) return null;
  if (meta.manual) {
    return 'This partner was cleared by a MANUAL screening attestation, not by a screening vendor. '
      + 'No automated list feed was consulted and nothing rescreens it as lists change.';
  }
  if (!meta.screened) {
    return 'No screening has been performed for this partner, so activation will be refused.';
  }
  return null;
}
