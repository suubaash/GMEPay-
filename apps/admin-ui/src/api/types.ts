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

export const TXN_STATUSES: readonly TxnStatus[] = [
  'CREATED',
  'QUOTED',
  'APPROVED',
  'FAILED',
  'CANCELLED',
  'SETTLED',
] as const;

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
  /** ISO 3166-1 alpha-2 country (e.g. "KR", "VN"). Optional for legacy rows. */
  country?: string;
  /** ISO-4217 currency this scheme settles in. Optional for legacy rows. */
  currency?: CurrencyCode;
  /** Operating mode label (e.g. "CPM", "MPM", "BOTH"). Optional. */
  mode?: string;
}

export interface SettlementBatch {
  batchId: string;
  partnerId: string;
  status: string;
  total: Money;
  createdAt: string;
}

/** A single settlement line within a {@link SettlementBatchDetail}. */
export interface SettlementLine {
  lineId: string;
  txnId: string;
  amount: Money;
  /** When true, the scheme-side report row matched our internal record. */
  matched: boolean;
  /** Optional discrepancy reason when `matched` is false. */
  reason?: string;
}

/** Full settlement batch with its constituent lines. */
export interface SettlementBatchDetail extends SettlementBatch {
  closedAt?: string;
  lines: SettlementLine[];
}

export interface RevenueSummary {
  periodStart: string;
  periodEnd: string;
  totalRevenue: Money;
  totalRoundingGain: Money;
  totalRoundingLoss: Money;
}

/** Per-row revenue breakdown across one dimension (partner | scheme | currency). */
export interface RevenueBreakdownRow {
  /** Dimension key — partner ID, scheme ID, or currency code depending on `dimension`. */
  key: string;
  dimension: 'partner' | 'scheme' | 'currency';
  revenue: Money;
  roundingGain: Money;
  roundingLoss: Money;
}

export interface RevenueBreakdown {
  periodStart: string;
  periodEnd: string;
  rows: RevenueBreakdownRow[];
}

/** Filters for the transactions search form. All optional. */
export interface TransactionSearchFilters {
  partnerId?: string;
  schemeId?: string;
  status?: TxnStatus;
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}

/**
 * Full transaction detail — includes the per-partner settlement rounding lock
 * (booked, mode, residual) that is rate-locked on commit per MONEY_CONVENTION.md.
 */
export interface TransactionDetail {
  id: string;
  partnerId: string;
  schemeId: string;
  status: TxnStatus;
  sendAmount: Money;
  collectionAmount: Money;
  payoutAmount?: Money;
  createdAt: string;
  approvedAt?: string;
  settledAt?: string;
  /** Settlement-booking lock — see docs/MONEY_CONVENTION.md. */
  settlementBookedAmount?: Money;
  settlementRoundingMode?: RoundingMode;
  settlementResidual?: Money;
}

/** Generic page envelope mirroring Spring's Page<T> JSON shape. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Webhook configuration row for an operator-managed endpoint. */
export interface WebhookConfigView {
  partnerId: string;
  url: string;
  events: string[];
  active: boolean;
}

/** Login request submitted by the /login page. */
export interface LoginRequest {
  username: string;
  password: string;
}

/** Login response — JWT bearer token + identity metadata. */
export interface LoginResponse {
  token: string;
  username: string;
  roles: string[];
  /** Epoch millis when the token expires. Optional; client treats as advisory. */
  expiresAt?: number;
}

/** Date range used by revenue queries. ISO date strings (YYYY-MM-DD). */
export interface DateRange {
  from: string;
  to: string;
}
