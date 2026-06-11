'use client';
import { configureStore } from '@reduxjs/toolkit';
import authReducer from './authSlice';
import overviewReducer from './overviewSlice';
import balanceReducer from './balanceSlice';
import transactionsReducer from './transactionsSlice';
import webhooksReducer from './webhooksSlice';
import profileReducer from './profileSlice';
import apiKeysReducer from './apiKeysSlice';
import statementReducer from './statementSlice';
import uiReducer from './uiSlice';

/**
 * The Partner Portal store is split into one focused slice per BFF resource
 * (overview, balance, transactions, webhooks, profile, apiKeys, statement)
 * plus auth state and a UI-prefs slice (dark mode).
 *
 * Each slice owns its own { data, status, error } shape so pages subscribe to
 * only what they render and avoid re-rendering on unrelated fetches.
 */
export const store = configureStore({
  reducer: {
    auth: authReducer,
    overview: overviewReducer,
    balance: balanceReducer,
    transactions: transactionsReducer,
    webhooks: webhooksReducer,
    profile: profileReducer,
    apiKeys: apiKeysReducer,
    statement: statementReducer,
    ui: uiReducer
  },
  middleware: (getDefault) =>
    getDefault({
      // Money values are plain strings + DTOs are JSON; everything is serializable.
      serializableCheck: true
    })
});
