'use client';

import { configureStore } from '@reduxjs/toolkit';
import { useDispatch, useSelector } from 'react-redux';
import partnersReducer from './partnersSlice';
import draftsReducer from './draftsSlice';
import transactionsReducer from './transactionsSlice';
import dashboardReducer from './dashboardSlice';
import schemesReducer from './schemesSlice';
import settlementReducer from './settlementSlice';
import revenueReducer from './revenueSlice';
import authReducer from './authSlice';
import ratesReducer from './ratesSlice';
import auditReducer from './auditSlice';
import systemHealthReducer from './systemHealthSlice';
import uiReducer from './uiSlice';

/**
 * Redux Toolkit root store for the admin-ui.
 *
 * Slices:
 *  - auth         : login state (JWT, username, role, loading/error).
 *  - dashboard    : 4 KPI cards on the home page.
 *  - partners     : list + detail cache + create/update flags (config-registry via BFF).
 *  - drafts       : in-flight Partner Setup wizard draft (Slice 1, ADR-012).
 *  - schemes      : QR scheme list.
 *  - transactions : paginated search + detail cache (txn-mgmt via BFF).
 *  - settlement   : recent batches + detail cache (settlement-reconciliation via BFF).
 *  - revenue      : period summary + breakdown (revenue-ledger via BFF).
 *  - rates        : manual rate-preview state (rate-fx via BFF, C4).
 *  - audit        : paginated audit log (config-registry via BFF, C4).
 *  - systemHealth : live service status (BFF aggregator, C4).
 *  - ui           : chrome-only state (palette mode, etc.).
 */
export const store = configureStore({
  reducer: {
    auth: authReducer,
    partners: partnersReducer,
    drafts: draftsReducer,
    transactions: transactionsReducer,
    dashboard: dashboardReducer,
    schemes: schemesReducer,
    settlement: settlementReducer,
    revenue: revenueReducer,
    rates: ratesReducer,
    audit: auditReducer,
    systemHealth: systemHealthReducer,
    ui: uiReducer,
  },
});

export const useAppDispatch = useDispatch;
export const useAppSelector = useSelector;
