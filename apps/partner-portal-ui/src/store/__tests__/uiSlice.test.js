import { describe, it, expect, beforeEach, vi } from 'vitest';
import reducer, { setMode, toggleMode, UI_MODE_KEY } from '../uiSlice';

/**
 * The uiSlice persists the chosen mode to localStorage so the user's
 * preference survives reloads. Tests verify both the reducer transitions
 * and the localStorage side-effect.
 */
describe('uiSlice', () => {
  beforeEach(() => {
    // jsdom provides localStorage; reset between tests.
    if (typeof window !== 'undefined') {
      window.localStorage.removeItem(UI_MODE_KEY);
    }
    vi.restoreAllMocks();
  });

  it('defaults to light mode', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state.mode).toBe('light');
  });

  it('toggleMode flips light -> dark and persists', () => {
    const state = reducer({ mode: 'light' }, toggleMode());
    expect(state.mode).toBe('dark');
    expect(window.localStorage.getItem(UI_MODE_KEY)).toBe('dark');
  });

  it('toggleMode flips dark -> light and persists', () => {
    const state = reducer({ mode: 'dark' }, toggleMode());
    expect(state.mode).toBe('light');
    expect(window.localStorage.getItem(UI_MODE_KEY)).toBe('light');
  });

  it('setMode accepts an explicit mode and persists it', () => {
    const state = reducer({ mode: 'light' }, setMode('dark'));
    expect(state.mode).toBe('dark');
    expect(window.localStorage.getItem(UI_MODE_KEY)).toBe('dark');
  });

  it('setMode rejects unknown values (defaults to light)', () => {
    const state = reducer({ mode: 'dark' }, setMode('purple'));
    expect(state.mode).toBe('light');
  });

  it('tolerates localStorage failures (does not throw)', () => {
    const original = window.localStorage.setItem.bind(window.localStorage);
    window.localStorage.setItem = () => {
      throw new Error('quota');
    };
    try {
      expect(() => reducer({ mode: 'light' }, toggleMode())).not.toThrow();
    } finally {
      window.localStorage.setItem = original;
    }
  });
});
