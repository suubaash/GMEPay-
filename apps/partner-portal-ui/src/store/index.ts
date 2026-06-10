'use client';
import { configureStore } from '@reduxjs/toolkit';
import portalReducer from './portalSlice';

export const store = configureStore({
  reducer: {
    portal: portalReducer
  },
  middleware: (getDefault) =>
    getDefault({
      // Money values are plain strings + DTOs are JSON; everything is serializable.
      serializableCheck: true
    })
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
