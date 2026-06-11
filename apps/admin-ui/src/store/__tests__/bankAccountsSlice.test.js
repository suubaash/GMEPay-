/**
 * bankAccountsSlice — contract tests for Slice 4A.2 (Banking & Settlement).
 *
 * Covers:
 *   - fetchBankAccounts lifecycle: populates byCode[partnerCode] on fulfilled.
 *   - verifyBankAccount lifecycle: replaces the updated account in byCode on fulfilled.
 *   - error states: stored and clearable.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchBankAccounts,
  verifyBankAccount,
  clearBankAccountError,
} from '@/store/bankAccountsSlice';

const PARTNER_CODE = 'GME_KR_001';

const ACCOUNT_1 = {
  id: 'ba-uuid-1',
  currency: 'USD',
  bankName: 'First National Bank',
  bicSwift: 'FNBKUSD1',
  ibanOrAccountNumber: '123456789',
  accountHolderName: 'GME Corp',
  bankCountry: 'US',
  intermediaryBic: null,
  swiftChargeBearer: 'SHA',
  purpose: 'PAYOUT',
  isPrimary: true,
  verificationStatus: 'UNVERIFIED',
  verificationDate: null,
};

const ACCOUNT_2 = {
  id: 'ba-uuid-2',
  currency: 'KRW',
  bankName: 'Hana Bank',
  bicSwift: 'HNBNKRSE',
  ibanOrAccountNumber: '110-123456-78',
  accountHolderName: 'GME Korea',
  bankCountry: 'KR',
  intermediaryBic: null,
  swiftChargeBearer: 'SHA',
  purpose: 'FLOAT_TOPUP',
  isPrimary: true,
  verificationStatus: 'KFTC_VERIFIED',
  verificationDate: '2026-06-10',
};

describe('bankAccountsSlice', () => {
  // ─── fetchBankAccounts ────────────────────────────────────────────────────

  it('fetchBankAccounts.pending sets loading[partnerCode] = true and clears error', () => {
    const state = reducer(undefined, {
      type: fetchBankAccounts.pending.type,
      meta: { arg: PARTNER_CODE },
    });
    expect(state.loading[PARTNER_CODE]).toBe(true);
    expect(state.error).toBeNull();
  });

  it('fetchBankAccounts.fulfilled populates byCode[partnerCode]', () => {
    const state = reducer(undefined, {
      type: fetchBankAccounts.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, accounts: [ACCOUNT_1, ACCOUNT_2] },
    });
    expect(state.loading[PARTNER_CODE]).toBe(false);
    expect(state.byCode[PARTNER_CODE]).toHaveLength(2);
    expect(state.byCode[PARTNER_CODE][0].id).toBe('ba-uuid-1');
    expect(state.byCode[PARTNER_CODE][1].id).toBe('ba-uuid-2');
  });

  it('fetchBankAccounts.fulfilled with empty array clears the list', () => {
    const seeded = reducer(undefined, {
      type: fetchBankAccounts.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, accounts: [ACCOUNT_1] },
    });
    const cleared = reducer(seeded, {
      type: fetchBankAccounts.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, accounts: [] },
    });
    expect(cleared.byCode[PARTNER_CODE]).toEqual([]);
  });

  it('fetchBankAccounts.rejected stores error message', () => {
    const state = reducer(undefined, {
      type: fetchBankAccounts.rejected.type,
      meta: { arg: PARTNER_CODE },
      error: { message: 'Network error' },
    });
    expect(state.loading[PARTNER_CODE]).toBe(false);
    expect(state.error).toBe('Network error');
  });

  it('fetchBankAccounts.rejected uses fallback message when error.message absent', () => {
    const state = reducer(undefined, {
      type: fetchBankAccounts.rejected.type,
      meta: { arg: PARTNER_CODE },
      error: {},
    });
    expect(state.error).toBe('Failed to load bank accounts');
  });

  // ─── verifyBankAccount ────────────────────────────────────────────────────

  it('verifyBankAccount.pending sets verifying[accountId] = true', () => {
    const state = reducer(undefined, {
      type: verifyBankAccount.pending.type,
      meta: { arg: { partnerCode: PARTNER_CODE, accountId: 'ba-uuid-1' } },
    });
    expect(state.verifying['ba-uuid-1']).toBe(true);
    expect(state.error).toBeNull();
  });

  it('verifyBankAccount.fulfilled replaces the account in byCode', () => {
    const seeded = reducer(undefined, {
      type: fetchBankAccounts.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, accounts: [ACCOUNT_1, ACCOUNT_2] },
    });

    const updated = {
      ...ACCOUNT_1,
      verificationStatus: 'MICRO_DEPOSIT',
      verificationDate: '2026-06-12',
    };

    const next = reducer(seeded, {
      type: verifyBankAccount.fulfilled.type,
      meta: { arg: { partnerCode: PARTNER_CODE, accountId: 'ba-uuid-1' } },
      payload: { partnerCode: PARTNER_CODE, account: updated },
    });

    expect(next.verifying['ba-uuid-1']).toBe(false);
    expect(next.byCode[PARTNER_CODE]).toHaveLength(2);
    const replaced = next.byCode[PARTNER_CODE].find((a) => a.id === 'ba-uuid-1');
    expect(replaced.verificationStatus).toBe('MICRO_DEPOSIT');
    expect(replaced.verificationDate).toBe('2026-06-12');
    // Second account should be untouched.
    const untouched = next.byCode[PARTNER_CODE].find((a) => a.id === 'ba-uuid-2');
    expect(untouched.verificationStatus).toBe('KFTC_VERIFIED');
  });

  it('verifyBankAccount.rejected stores error and clears verifying', () => {
    const pending = reducer(undefined, {
      type: verifyBankAccount.pending.type,
      meta: { arg: { partnerCode: PARTNER_CODE, accountId: 'ba-uuid-1' } },
    });
    const next = reducer(pending, {
      type: verifyBankAccount.rejected.type,
      meta: { arg: { partnerCode: PARTNER_CODE, accountId: 'ba-uuid-1' } },
      error: { message: 'Verify failed' },
    });
    expect(next.verifying['ba-uuid-1']).toBe(false);
    expect(next.error).toBe('Verify failed');
  });

  it('verifyBankAccount.rejected uses fallback message when error.message absent', () => {
    const state = reducer(undefined, {
      type: verifyBankAccount.rejected.type,
      meta: { arg: { partnerCode: PARTNER_CODE, accountId: 'ba-uuid-1' } },
      error: {},
    });
    expect(state.error).toBe('Verification request failed');
  });

  // ─── clearBankAccountError ────────────────────────────────────────────────

  it('clearBankAccountError nulls the error field', () => {
    const withError = reducer(undefined, {
      type: fetchBankAccounts.rejected.type,
      meta: { arg: PARTNER_CODE },
      error: { message: 'some error' },
    });
    expect(withError.error).toBe('some error');

    const cleared = reducer(withError, clearBankAccountError());
    expect(cleared.error).toBeNull();
  });
});
