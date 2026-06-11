import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import React from 'react';

// lottie-react's underlying lottie-web touches HTMLCanvasElement at module
// init; jsdom returns null for getContext('2d'). Stub it globally so every
// page that pulls in EmptyState (audit, system-health, etc.) can be tested.
vi.mock('lottie-react', () => ({
  __esModule: true,
  default: () => React.createElement('div', { 'data-testid': 'lottie' }),
}));
