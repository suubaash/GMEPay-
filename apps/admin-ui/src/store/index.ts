'use client';

import { configureStore } from '@reduxjs/toolkit';
import { TypedUseSelectorHook, useDispatch, useSelector } from 'react-redux';
import partnersReducer from './partnersSlice';
import transactionsReducer from './transactionsSlice';
import dashboardReducer from './dashboardSlice';

/**
 * Redux Toolkit root store for the admin-ui.
 *
 * Slices:
 *  - partners: list + cached detail records (config-registry projections via BFF).
 *  - transactions: recent transactions feed.
 *  - dashboard: 4 KPI cards on the home page.
 */
export const store = configureStore({
  reducer: {
    partners: partnersReducer,
    transactions: transactionsReducer,
    dashboard: dashboardReducer,
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;

export const useAppDispatch: () => AppDispatch = useDispatch;
export const useAppSelector: TypedUseSelectorHook<RootState> = useSelector;
