'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import type {
  BalanceDto,
  PagedResponse,
  PartnerProfileDto,
  TransactionSummaryDto
} from '@/api/types';

type LoadState = 'idle' | 'loading' | 'succeeded' | 'failed';

interface PortalState {
  balance: { data: BalanceDto | null; status: LoadState; error: string | null };
  transactions: {
    data: PagedResponse<TransactionSummaryDto> | null;
    status: LoadState;
    error: string | null;
  };
  profile: { data: PartnerProfileDto | null; status: LoadState; error: string | null };
}

const initialState: PortalState = {
  balance: { data: null, status: 'idle', error: null },
  transactions: { data: null, status: 'idle', error: null },
  profile: { data: null, status: 'idle', error: null }
};

export const fetchBalance = createAsyncThunk(
  'portal/fetchBalance',
  async (partnerId: string) => portalApi.getBalance(partnerId)
);

export const fetchTransactions = createAsyncThunk(
  'portal/fetchTransactions',
  async (arg: { partnerId: string; page: number; size: number }) =>
    portalApi.listTransactions(arg.partnerId, arg.page, arg.size)
);

export const fetchProfile = createAsyncThunk(
  'portal/fetchProfile',
  async (partnerId: string) => portalApi.getProfile(partnerId)
);

const portalSlice = createSlice({
  name: 'portal',
  initialState,
  reducers: {
    resetPortal: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchBalance.pending, (s) => {
        s.balance.status = 'loading';
        s.balance.error = null;
      })
      .addCase(fetchBalance.fulfilled, (s, a: PayloadAction<BalanceDto>) => {
        s.balance.status = 'succeeded';
        s.balance.data = a.payload;
      })
      .addCase(fetchBalance.rejected, (s, a) => {
        s.balance.status = 'failed';
        s.balance.error = a.error.message ?? 'Failed to load balance';
      })

      .addCase(fetchTransactions.pending, (s) => {
        s.transactions.status = 'loading';
        s.transactions.error = null;
      })
      .addCase(
        fetchTransactions.fulfilled,
        (s, a: PayloadAction<PagedResponse<TransactionSummaryDto>>) => {
          s.transactions.status = 'succeeded';
          s.transactions.data = a.payload;
        }
      )
      .addCase(fetchTransactions.rejected, (s, a) => {
        s.transactions.status = 'failed';
        s.transactions.error = a.error.message ?? 'Failed to load transactions';
      })

      .addCase(fetchProfile.pending, (s) => {
        s.profile.status = 'loading';
        s.profile.error = null;
      })
      .addCase(fetchProfile.fulfilled, (s, a: PayloadAction<PartnerProfileDto>) => {
        s.profile.status = 'succeeded';
        s.profile.data = a.payload;
      })
      .addCase(fetchProfile.rejected, (s, a) => {
        s.profile.status = 'failed';
        s.profile.error = a.error.message ?? 'Failed to load profile';
      });
  }
});

export const { resetPortal } = portalSlice.actions;
export default portalSlice.reducer;
