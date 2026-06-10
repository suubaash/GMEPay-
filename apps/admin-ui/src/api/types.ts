/**
 * TypeScript shapes mirroring the Ops/Partner BFF DTOs.
 *
 * The BFF aggregates several backend services (config-registry, transaction-mgmt,
 * settlement-reconciliation, revenue-ledger). Money is exchanged as a decimal
 * STRING per docs/MONEY_CONVENTION.md (never as a JS number) so JS float
 * imprecision can never round it.
 */

/** ISO-4217 currency code (3 uppercase letters). */
export type CurrencyCode = string;

/** Money as a decimal string + ISO-4217 currency, as the BFF emits it. */
export interface Money {
  amount: string;
  currency: CurrencyCode;
}

/**
 * Per-partner rounding mode for booking settlement liability.
 * Mirrors java.math.RoundingMode (subset relevant to settlement booking).
 * See docs/MONEY_CONVENTION.md.
 */
export type RoundingMode =
  | 'HALF_UP'
  | 'HALF_DOWN'
  | 'HALF_EVEN'
  | 'DOWN'
  | 'UP'
  | 'CEILING'
  | 'FLOOR';

export const ROUNDING_MODES: readonly RoundingMode[] = [
  'HALF_UP',
  'HALF_DOWN',
  'HALF_EVEN',
  'DOWN',
  'UP',
  'CEILING',
  'FLOOR',
] as const;

/** Mirrors com.gme.pay.domain.PartnerType. */
export type PartnerType = 'LOCAL' | 'OVERSEAS';

export const PARTNER_TYPES: readonly PartnerType[] = ['LOCAL', 'OVERSEAS'] as const;

/** Row shape for the partner list view (BFF projection). */
export interface PartnerSummary {
  partnerId: string;
  type: PartnerType;
  settlementCurrency: CurrencyCode;
  settlementRoundingMode: RoundingMode;
}

/** Full partner record (list row + audit timestamps from config-registry). */
export interface PartnerDetail extends PartnerSummary {
  createdAt?: string;
  updatedAt?: string;
}

/** Payload submitted by the partner CREATE form to POST /v1/admin/partners. */
export interface PartnerCreateRequest {
  partnerId: string;
  type: PartnerType;
  settlementCurrency: CurrencyCode;
  settlementRoundingMode: RoundingMode;
}

/** Recent-transaction row from the BFF. */
export interface RecentTxn {
  id: string;
  partnerId: string;
  status: TxnStatus;
  amount: Money;
  scheme: string;
  createdAt: string;
}

export type TxnStatus =
  | 'CREATED'
  | 'QUOTED'
  | 'APPROVED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SETTLED';

/** Card metrics on the Dashboard page. */
export interface AdminDashboard {
  txnCountToday: number;
  approvedVolumeToday: Money;
  activePartners: number;
  rollingFailureRate: number; // 0..1
}

export interface QrScheme {
  schemeId: string;
  displayName: string;
  active: boolean;
}

export interface SettlementBatch {
  batchId: string;
  partnerId: string;
  status: string;
  total: Money;
  createdAt: string;
}

export interface RevenueSummary {
  periodStart: string;
  periodEnd: string;
  totalRevenue: Money;
  totalRoundingGain: Money;
  totalRoundingLoss: Money;
}
