'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Settlement-statement slice (gap T4-5).
 *
 * Wire shape — GET /v1/portal/{partnerId}/settlements?from&to&includeLines returns the partner's
 * SETTLED record, summed from settlement-reconciliation's persisted settlement_batches /
 * settlement_lines:
 *
 *   {
 *     partnerId: string,
 *     from: string|null, to: string|null,        // resolved ISO window (the BFF/service anchors it)
 *     currency: string|null,                     // null when the window is empty
 *     entries: Array<{
 *       batch: {
 *         batchId: string,                       // the REAL persisted batch id
 *         partnerId: string,
 *         settlementDate: string,                // ISO date
 *         currency: string,
 *         amount: string,                        // BigDecimal-as-string, batch-level net
 *         status: string,                        // PENDING|GENERATED|TRANSMITTED|RECEIVED|
 *                                                // RECONCILED|ERROR|UNKNOWN
 *         transmissionState: string,             // NOT_TRANSMITTED|
 *                                                // NOT_TRANSMITTED_CHANNEL_UNAVAILABLE|
 *                                                // TRANSMISSION_FAILED|TRANSMITTED|UNKNOWN
 *         transmissionReason: string|null,
 *         transmittedAt: string|null             // null unless transmissionState === TRANSMITTED
 *       },
 *       netSettlementAmount: string,             // THIS partner's share, not the batch total
 *       paymentAmount: string,
 *       clawbackAmount: string,
 *       lineCount: number, openLineCount: number,
 *       lines: Array<{ txnRef, amount, currency, matched }>
 *     }>,
 *     netSettlementAmount: string, paymentAmount: string, clawbackAmount: string,
 *     lineCount: number, openLineCount: number,
 *     transmittedEntryCount: number,             // 0 in every environment today
 *     transmissionChannel: { live: boolean, reachableState: string, reason: string|null }
 *   }
 *
 * MONEY IS A STRING. Never Number()-cast `netSettlementAmount` / `amount` for arithmetic; render
 * through MoneyDisplay.
 *
 * TWO AXES, NOT ONE. `status` says how far reconciliation got; `transmissionState` says whether the
 * file ever left the platform. A RECONCILED batch with
 * transmissionState=NOT_TRANSMITTED_CHANNEL_UNAVAILABLE is the normal case today: GMEPay+ booked and
 * reconciled it, and sent nothing, because no settlement transmission channel is configured anywhere
 * (scheme SFTP credentials + certification are externally gated). A page that renders only `status`
 * would tell a partner their money was instructed to the scheme when it was not.
 *
 * READ-ONLY. There is no settlement write thunk here on purpose — partner self-serve settlement
 * actions are an open product decision (gap T1-5), and stubbing one would be the same "looks
 * available, isn't" defect T4-5 exists to remove.
 *
 * State: { data, status, error }
 */

const initialState = { data: null, status: 'idle', error: null };

export const fetchSettlements = createAsyncThunk(
  'settlements/fetch',
  async ({ partnerId, from, to, includeLines = true } = {}) =>
    portalApi.getSettlements(partnerId, { from, to, includeLines })
);

const slice = createSlice({
  name: 'settlements',
  initialState,
  reducers: {
    resetSettlements: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchSettlements.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(fetchSettlements.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.data = a.payload;
      })
      .addCase(fetchSettlements.rejected, (s, a) => {
        s.status = 'failed';
        // Deliberately does NOT clear `data`: a failed refresh must not silently replace a real
        // statement with an empty one that reads as "nothing was settled".
        s.error = a.error?.message ?? 'Failed to load the settlement statement';
      });
  }
});

export const { resetSettlements } = slice.actions;
export default slice.reducer;

/**
 * True only when the payload says, in the service's own vocabulary, that this batch was transmitted.
 * Anything else — including UNKNOWN and a missing field — is not a transmission.
 */
export function isTransmitted(batch) {
  return batch?.transmissionState === 'TRANSMITTED';
}
