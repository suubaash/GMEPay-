/**
 * Contract lock for the BFF transactions endpoints.
 *
 * Page envelope (services/.../web/dto/Page.java):
 *   record Page<T>(List<T> content, int page, int size, long total) {}
 *
 * TransactionSummary (TransactionMgmtClient.java):
 *   { txnId, partnerId, state, amount (BigDecimal string), currency, committedAt }
 *
 * TransactionDetail (web/dto/TransactionDetail.java):
 *   { summary, schemeTxnRef, schemeApprovalCode,
 *     prefundDeductedUsd, approvedAt,
 *     bookedSettlementAmount, settlementRoundingMode, roundingResidual }
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  searchTransactions,
  getTransaction,
} from '@/store/transactionsSlice';

const SUMMARY = {
  txnId: 'TXN-1001',
  partnerId: 'GME_KR_001',
  state: 'APPROVED',
  amount: '10500.567',
  currency: 'USD',
  committedAt: '2026-06-09T12:34:56Z',
};

const PAGE = {
  content: [SUMMARY],
  page: 0,
  size: 20,
  total: 1,
};

const DETAIL = {
  summary: SUMMARY,
  schemeTxnRef: 'SCH-TXN-1001',
  schemeApprovalCode: 'AP-TXN-1001',
  prefundDeductedUsd: '10500.567',
  approvedAt: '2026-06-09T12:34:54Z',
  bookedSettlementAmount: '10500.57',
  settlementRoundingMode: 'HALF_UP',
  roundingResidual: '-0.003',
};

describe('transactionsSlice', () => {
  it('stores Page<TransactionSummary> with `content`, `page`, `size`, `total`', () => {
    const next = reducer(undefined, {
      type: searchTransactions.fulfilled.type,
      payload: PAGE,
    });
    expect(next.items).toEqual(PAGE.content);
    expect(next.page).toBe(0);
    expect(next.size).toBe(20);
    expect(next.total).toBe(1);
    // BFF field names must be exact:
    expect(next.items[0].txnId).toBe('TXN-1001');
    expect(next.items[0].state).toBe('APPROVED');
    expect(next.items[0].committedAt).toBe('2026-06-09T12:34:56Z');
    expect(next.items[0].amount).toBe('10500.567');
  });

  it('caches TransactionDetail keyed by summary.txnId (NOT a flat `id`)', () => {
    const next = reducer(undefined, {
      type: getTransaction.fulfilled.type,
      payload: DETAIL,
    });
    expect(next.details['TXN-1001']).toEqual(DETAIL);
    expect(next.details['TXN-1001'].bookedSettlementAmount).toBe('10500.57');
    expect(next.details['TXN-1001'].settlementRoundingMode).toBe('HALF_UP');
    expect(next.details['TXN-1001'].roundingResidual).toBe('-0.003');
  });

  it('defaults to empty content when the BFF returns no rows', () => {
    const empty = { content: [], page: 0, size: 20, total: 0 };
    const next = reducer(undefined, {
      type: searchTransactions.fulfilled.type,
      payload: empty,
    });
    expect(next.items).toEqual([]);
    expect(next.total).toBe(0);
  });
});
