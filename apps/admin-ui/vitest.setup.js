import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import React from 'react';

// Tests run without a live Keycloak. Enable the dev escape hatch so the
// /login form keeps rendering its dev-credentials section and AuthGate
// redirects to /login (not Keycloak). The flag is documented in
// src/api/oidc.js#isDevLoginAllowed.
process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN = 'true';

// lottie-react's underlying lottie-web touches HTMLCanvasElement at module
// init; jsdom returns null for getContext('2d'). Stub it globally so every
// page that pulls in EmptyState (audit, system-health, etc.) can be tested.
vi.mock('lottie-react', () => ({
  __esModule: true,
  default: () => React.createElement('div', { 'data-testid': 'lottie' }),
}));
