/**
 * Runtime enum-like constants exposed by the BFF.
 *
 * These mirror values defined in:
 *   - com.gme.pay.domain.PartnerType (LOCAL | OVERSEAS)
 *   - java.math.RoundingMode (the 7 modes settlement booking allows)
 *   - transaction-mgmt's state machine (CREATED -> ... -> SETTLED)
 *
 * Type information for shapes is documented inline via JSDoc on the
 * consumers (slices, components). The runtime values live here.
 */

/** The 7 java.math.RoundingMode values used by settlement booking. */
export const ROUNDING_MODES = [
  'HALF_UP',
  'HALF_DOWN',
  'HALF_EVEN',
  'DOWN',
  'UP',
  'CEILING',
  'FLOOR',
];

/** Partner taxonomy (com.gme.pay.domain.PartnerType). */
export const PARTNER_TYPES = ['LOCAL', 'OVERSEAS'];

/**
 * Transaction lifecycle states. The BFF returns this on
 * TransactionSummary.state (NOT `status`).
 */
export const TXN_STATES = [
  'CREATED',
  'QUOTED',
  'APPROVED',
  'FAILED',
  'CANCELLED',
  'SETTLED',
];

/** @deprecated old name. Keep until all imports migrate. */
export const TXN_STATUSES = TXN_STATES;
