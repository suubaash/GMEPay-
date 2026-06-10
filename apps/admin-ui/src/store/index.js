'use client';

import { configureStore } from '@reduxjs/toolkit';
import { useDispatch, useSelector } from 'react-redux';
import partnersReducer from './partnersSlice';
import transactionsReducer from './transactionsSlice';
import dashboardReducer from './dashboardSlice';
import schemesReducer from './schemesSlice';
import settlementReducer from './settlementSlice';
import revenueReducer from './revenueSlice';
import authReducer from './authSlice';

/**
 * Redux Toolkit root store for the admin-ui.
 *
 * Slices:
 *  - auth         : login state (JWT, username, role, loading/error).
 *  - dashboard    : 4 KPI cards on the home page.
 *  - partners     : list + detail cache + create/update flags (config-registry via BFF).
 *  - schemes      : QR scheme list.
 *  - transactions : paginated search + detail cache (txn-mgmt via BFF).
 *  - settlement   : recent batches + detail cache (settlement-reconciliation via BFF).
 *  - revenue      : period summary + breakdown (revenue-ledger via BFF).
 */
export const store = configureStore({
  reducer: {
    auth: authReducer,
    partners: partnersReducer,
    transactions: transactionsReducer,
    dashboard: dashboardReducer,
    schemes: schemesReducer,
    settlement: settlementReducer,
    revenue: revenueReducer,
  },
});

export const useAppDispatch = useDispatch;
export const useAppSelector = useSelector;
