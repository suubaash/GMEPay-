'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import type {
  PagedResponse,
  TransactionDetailDto,
  TransactionSummaryDto
} from '@/api/types';
import type { LoadState } from './overviewSlice';

interface TransactionsState {
  page: {
    data: PagedResponse<TransactionSummaryDto> | null;
    status: LoadState;
    error: string | null;
  };
  detail: {
    data: TransactionDetailDto | null;
    status: LoadState;
    error: string | null;
    /** HTTP status of the most recent failure (for 404-vs-500 branching). */
    failureStatus: number | null;
  };
}

const initialState: TransactionsState = {
  page: { data: null, status: 'idle', error: null },
  detail: { data: null, status: 'idle', error: null, failureStatus: null }
};

export interface FetchTxnPageArg {
  partnerId: string;
  page: number;
  size: number;
  sort?: string;
}

export const fetchTransactionsPage = createAsyncThunk(
  'transactions/fetchPage',
  async (arg: FetchTxnPageArg) =>
    portalApi.listTransactions(arg.partnerId, arg.page, arg.size, arg.sort ?? 'createdAt,desc')
);

export interface FetchTxnDetailArg {
  partnerId: string;
  txnId: string;
}

export const fetchTransactionDetail = createAsyncThunk(
  'transactions/fetchDetail',
  async (arg: FetchTxnDetailArg, { rejectWithValue }) => {
    try {
      return await portalApi.getTransaction(arg.partnerId, arg.txnId);
    } catch (e) {
      const status =
        (e as { status?: number }).status ?? (e instanceof Error ? 500 : 500);
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
      .addCase(fetchTransactionsPage.pending, (s) => {
        s.page.status = 'loading';
        s.page.error = null;
      })
      .addCase(
        fetchTransactionsPage.fulfilled,
        (s, a: PayloadAction<PagedResponse<TransactionSummaryDto>>) => {
          s.page.status = 'succeeded';
          s.page.data = a.payload;
        }
      )
      .addCase(fetchTransactionsPage.rejected, (s, a) => {
        s.page.status = 'failed';
        s.page.error = a.error.message ?? 'Failed to load transactions';
      })

      .addCase(fetchTransactionDetail.pending, (s) => {
        s.detail.status = 'loading';
        s.detail.error = null;
        s.detail.failureStatus = null;
      })
      .addCase(
        fetchTransactionDetail.fulfilled,
        (s, a: PayloadAction<TransactionDetailDto>) => {
          s.detail.status = 'succeeded';
          s.detail.data = a.payload;
        }
      )
      .addCase(fetchTransactionDetail.rejected, (s, a) => {
        s.detail.status = 'failed';
        const payload = a.payload as { status?: number; message?: string } | undefined;
        s.detail.failureStatus = payload?.status ?? 500;
        s.detail.error =
          payload?.message ?? a.error.message ?? 'Failed to load transaction';
      });
  }
});

export const { resetTransactions, clearDetail } = slice.actions;
export default slice.reducer;
