'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Transactions slice (list + detail).
 *
 * Wire shapes:
 *   GET /v1/portal/{partnerId}/transactions
 *     -> Array<TransactionSummary>
 *        { txnId, partnerId, state, amount, currency, committedAt }
 *
 *   GET /v1/portal/{partnerId}/transactions/{txnId}
 *     -> TransactionDetail
 *        {
 *          summary: TransactionSummary,
 *          schemeTxnRef: string,
 *          schemeApprovalCode: string,
 *          prefundDeductedUsd: string,    // BigDecimal-as-string
 *          approvedAt: string,            // ISO instant
 *          bookedSettlementAmount: string,
 *          settlementRoundingMode: string,
 *          roundingResidual: string
 *        }
 *
 * The Portal endpoint returns a plain List (NOT the Page<T> envelope used by
 * the Admin transactions endpoint). The list slice mirrors that and exposes
 * { items, total } so page components can do simple client-side paging.
 *
 * 404 on detail (transaction not found or not owned by this partner) is
 * captured separately so the detail page can redirect.
 */

const initialState = {
  list: { items: [], status: 'idle', error: null },
  detail: { data: null, status: 'idle', error: null, failureStatus: null }
};

export const fetchTransactions = createAsyncThunk(
  'transactions/fetchList',
  async (arg) => {
    const partnerId = typeof arg === 'string' ? arg : arg.partnerId;
    const limit = typeof arg === 'object' && arg && arg.limit ? arg.limit : 100;
    return portalApi.listTransactions(partnerId, limit);
  }
);

export const fetchTransactionDetail = createAsyncThunk(
  'transactions/fetchDetail',
  async (arg, { rejectWithValue }) => {
    try {
      return await portalApi.getTransaction(arg.partnerId, arg.txnId);
    } catch (e) {
      const status = (e && e.status) ?? 500;
      return rejectWithValue({
        status,
        message: e instanceof Error ? e.message : 'Failed to load transaction'
      });
    }
  }
);

const slice = createSlice({
  name: 'transactions',
  initialState,
  reducers: {
    resetTransactions: () => initialState,
    clearDetail: (s) => {
      s.detail = { data: null, status: 'idle', error: null, failureStatus: null };
    }
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchTransactions.pending, (s) => {
        s.list.status = 'loading';
        s.list.error = null;
      })
      .addCase(fetchTransactions.fulfilled, (s, a) => {
        s.list.status = 'succeeded';
        s.list.items = Array.isArray(a.payload) ? a.payload : [];
      })
      .addCase(fetchTransactions.rejected, (s, a) => {
        s.list.status = 'failed';
        s.list.error = a.error.message ?? 'Failed to load transactions';
      })

      .addCase(fetchTransactionDetail.pending, (s) => {
        s.detail.status = 'loading';
        s.detail.error = null;
        s.detail.failureStatus = null;
      })
      .addCase(fetchTransactionDetail.fulfilled, (s, a) => {
        s.detail.status = 'succeeded';
        s.detail.data = a.payload;
      })
      .addCase(fetchTransactionDetail.rejected, (s, a) => {
        s.detail.status = 'failed';
        const payload = a.payload;
        s.detail.failureStatus = (payload && payload.status) ?? 500;
        s.detail.error =
          (payload && payload.message) ?? a.error.message ?? 'Failed to load transaction';
      });
  }
});

export const { resetTransactions, clearDetail } = slice.actions;
export default slice.reducer;
