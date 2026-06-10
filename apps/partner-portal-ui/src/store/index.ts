'use client';
import { configureStore } from '@reduxjs/toolkit';
import authReducer from './authSlice';
import overviewReducer from './overviewSlice';
import balanceReducer from './balanceSlice';
import transactionsReducer from './transactionsSlice';
import webhooksReducer from './webhooksSlice';
import profileReducer from './profileSlice';

/**
 * The Partner Portal store is split into one focused slice per BFF resource
 * (overview, balance, transactions, webhooks, profile) plus auth state.
 *
 * Each slice owns its own `{ data, status, error }` shape so pages can
 * subscribe to only what they render and avoid re-rendering on unrelated
 * fetches. The legacy `portalSlice` is no longer wired into the store —
 * callers should import the focused slices.
 */
export const store = configureStore({
  reducer: {
    auth: authReducer,
    overview: overviewReducer,
    balance: balanceReducer,
    transactions: transactionsReducer,
    webhooks: webhooksReducer,
    profile: profileReducer
  },
  middleware: (getDefault) =>
    getDefault({
      // Money values are plain strings + DTOs are JSON; everything is serializable.
      serializableCheck: true
    })
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
