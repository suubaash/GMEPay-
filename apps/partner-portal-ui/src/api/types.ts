/**
 * DTOs returned by the Ops/Partner BFF under /v1/portal/{partnerId}/*.
 *
 * Money fields are decimal strings + ISO-4217 currency per docs/MONEY_CONVENTION.md
 * (BigDecimal in major units; never doubles, never integer minor units).
 */

export type Iso4217 = string; // e.g. "USD", "KRW", "NPR"

export type RoundingMode =
  | 'UP'
  | 'DOWN'
  | 'CEILING'
  | 'FLOOR'
  | 'HALF_UP'
  | 'HALF_DOWN'
  | 'HALF_EVEN'
  | 'UNNECESSARY';

export interface MoneyDto {
  /** Decimal string in major units, e.g. "10.20" or "50000" */
  amount: string;
  currency: Iso4217;
}

export interface BalanceDto {
  partnerId: string;
  balance: MoneyDto;
  /** Threshold below which `prefunding.low` events fire. */
  lowBalanceThreshold: MoneyDto;
  /** ISO-8601 timestamp of the last ledger write affecting this balance. */
  lastUpdatedAt: string;
  /** ISO-8601 timestamp of the most recent settlement, if any. */
  lastSettlementAt?: string | null;
}

export type TransactionStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'FAILED'
  | 'CANCELLED'
  | 'REVERSED'
  | 'SETTLED';

export interface TransactionSummaryDto {
  txnId: string;
  partnerId: string;
  status: TransactionStatus;
  sendAmount: MoneyDto;
  payoutAmount: MoneyDto;
  createdAt: string;
  scheme?: string | null;
}

export interface TransactionDetailDto extends TransactionSummaryDto {
  /** Rate locked at quote time (decimal string). */
  rate: string;
  /** Booked settlement amount under partner's rounding rule. */
  bookedSettlementAmount: MoneyDto;
  /** Rounding mode used to book settlement. */
  settlementRoundingMode: RoundingMode;
  /** Residual (precise - booked) posted to REVENUE_ROUNDING. */
  roundingResidual: MoneyDto;
  events: TransactionEventDto[];
}

export interface TransactionEventDto {
  at: string;
  type: string;
  detail?: string;
}

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface WebhookConfigDto {
  id: string;
  url: string;
  events: string[];
  /** Whether webhook is currently active. Edit is Phase 2. */
  active: boolean;
  createdAt: string;
  lastDeliveryAt?: string | null;
  lastDeliveryStatus?: 'OK' | 'FAILED' | null;
}

export type PartnerType = 'OVERSEAS' | 'DOMESTIC';

export interface PartnerProfileDto {
  partnerId: string;
  displayName: string;
  type: PartnerType;
  settlementCurrency: Iso4217;
  settlementRoundingMode: RoundingMode;
  /** Onboarded-at ISO timestamp. */
  onboardedAt: string;
}

export interface ApiError {
  code: string;
  message: string;
  traceId?: string;
}
