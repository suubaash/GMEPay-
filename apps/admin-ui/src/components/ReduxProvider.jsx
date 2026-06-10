'use client';

import { Provider } from 'react-redux';
import { store } from '@/store';

/** Client-side Redux Provider; wraps the App Router tree in the root layout. */
export default function ReduxProvider({ children }) {
  return <Provider store={store}>{children}</Provider>;
}
