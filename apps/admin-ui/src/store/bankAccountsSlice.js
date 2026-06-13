'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Bank accounts slice — backs the Step 4 Banking & Settlement form in the
 * Partner Setup wizard (Slice 4, ADR-010 bitemporal SCD-6).
 *
 * Manages:
 *   - `byCode`       : map of partnerCode → BankAccountView[] (cached per-partner).
 *   - `loading`      : map of partnerCode → boolean — fetch in-flight.
 *   - `verifying`    : map of bankAccountId → boolean — verify in-flight per row.
 *   - `error`        : last user-visible failure from bank-account operations.
 *
 * The PATCH that persists the bank account list goes through the existing
 * `patchStep4` thunk in draftsSlice (adminApi.patchDraftStep(4, ...)).
 * This slice handles the read path (GET) and per-row verification (POST).
 *
 * BankAccountView shape (from GET /api/v1/admin/partners/{code}/bank-accounts):
 *   {
 *     id:                   UUID string,
 *     currency:             string,
 *     bankName:             string,
 *     bicSwift:             string,
 *     ibanOrAccountNumber:  string,
 *     accountHolderName:    string,
 *     bankCountry:          string,
 *     intermediaryBic:      string|null,
 *     swiftChargeBearer:    'OUR'|'BEN'|'SHA',
 *     purpose:              'PAYOUT'|'FLOAT_TOPUP'|'REFUND',
 *     isPrimary:            boolean,
 *     verificationStatus:   'UNVERIFIED'|'KFTC_VERIFIED'|'BANK_LETTER'|'MICRO_DEPOSIT',
 *     verificationDate:     ISO-8601 date string|null,
 *   }
 *
 * NOTE (Slice 8 deferred): The 2-authorized-signatory approval flow for
 * POST-ACTIVATION bank-account changes is deferred to Slice 8 (FSM). During
 * onboarding drafts, bank-account writes go direct (audited). This slice
 * does not implement the approval gate.
 */
const initialState = {
  /** partnerCode → BankAccountView[] */
  byCode: {},
  /** partnerCode → boolean */
  loading: {},
  /** bankAccountId → boolean */
  verifying: {},
  error: null,
};

/**
 * GET /v1/admin/partners/{partnerCode}/bank-accounts -> BankAccountView[]
 */
export const fetchBankAccounts = createAsyncThunk(
  'bankAccounts/fetch',
  async (partnerCode) => {
    const accounts = await adminApi.getBankAccounts(partnerCode);
    return { partnerCode, accounts };
  },
);

/**
 * POST /v1/admin/partners/{partnerCode}/bank-accounts/{id}/verify
 * -> BankAccountView (refreshed)
 */
export const verifyBankAccount = createAsyncThunk(
  'bankAccounts/verify',
  async ({ partnerCode, accountId }) => {
    const account = await adminApi.verifyBankAccount(partnerCode, accountId);
    return { partnerCode, account };
  },
);

const bankAccountsSlice = createSlice({
  name: 'bankAccounts',
  initialState,
  reducers: {
    clearBankAccountError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchBankAccounts ----
      .addCase(fetchBankAccounts.pending, (state, action) => {
        const code = action.meta.arg;
        state.loading[code] = true;
        state.error = null;
      })
      .addCase(fetchBankAccounts.fulfilled, (state, action) => {
        const { partnerCode, accounts } = action.payload;
        state.loading[partnerCode] = false;
        state.byCode[partnerCode] = Array.isArray(accounts) ? accounts : [];
      })
      .addCase(fetchBankAccounts.rejected, (state, action) => {
        const code = action.meta.arg;
        state.loading[code] = false;
        state.error = action.error?.message ?? 'Failed to load bank accounts';
      })
      // ---- verifyBankAccount ----
      .addCase(verifyBankAccount.pending, (state, action) => {
        const { accountId } = action.meta.arg;
        state.verifying[accountId] = true;
        state.error = null;
      })
      .addCase(verifyBankAccount.fulfilled, (state, action) => {
        const { partnerCode, account } = action.payload;
        const { accountId } = action.meta.arg;
        state.verifying[accountId] = false;
        // Replace the updated account in the cached list.
        if (account && Array.isArray(state.byCode[partnerCode])) {
          state.byCode[partnerCode] = state.byCode[partnerCode].map((a) =>
            a.id === account.id ? account : a,
          );
        }
      })
      .addCase(verifyBankAccount.rejected, (state, action) => {
        const { accountId } = action.meta.arg;
        state.verifying[accountId] = false;
        state.error = action.error?.message ?? 'Verification request failed';
      });
  },
});

export const { clearBankAccountError } = bankAccountsSlice.actions;
export default bankAccountsSlice.reducer;
