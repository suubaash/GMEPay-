/**
 * uiSlice covers only the chrome (theme palette mode) — it has no BFF
 * contract to lock. The tests assert the toggle/setMode/hydrateMode
 * behaviour and that toggleMode persists to localStorage under the
 * documented key.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import reducer, {
  toggleMode,
  setMode,
  hydrateMode,
  UI_MODE_KEY,
} from '@/store/uiSlice';

describe('uiSlice', () => {
  beforeEach(() => {
    if (typeof window !== 'undefined') {
      try {
        window.localStorage.removeItem(UI_MODE_KEY);
      } catch {
        /* ignore */
      }
    }
  });

  it('defaults to light mode', () => {
    const next = reducer(undefined, { type: '@@INIT' });
    expect(next.mode).toBe('light');
  });

  it('toggleMode flips light <-> dark and persists to localStorage', () => {
    const setItem = vi.spyOn(window.localStorage.__proto__, 'setItem');
    const next = reducer(undefined, toggleMode());
    expect(next.mode).toBe('dark');
    expect(setItem).toHaveBeenCalledWith(UI_MODE_KEY, 'dark');
    const back = reducer(next, toggleMode());
    expect(back.mode).toBe('light');
    expect(setItem).toHaveBeenCalledWith(UI_MODE_KEY, 'light');
    setItem.mockRestore();
  });

  it('setMode("dark") forces dark and persists', () => {
    const setItem = vi.spyOn(window.localStorage.__proto__, 'setItem');
    const next = reducer(undefined, setMode('dark'));
    expect(next.mode).toBe('dark');
    expect(setItem).toHaveBeenCalledWith(UI_MODE_KEY, 'dark');
    setItem.mockRestore();
  });

  it('setMode normalises unknown values to "light"', () => {
    const next = reducer({ mode: 'dark' }, setMode('bogus'));
    expect(next.mode).toBe('light');
  });

  it('hydrateMode updates state WITHOUT writing back to localStorage', () => {
    const setItem = vi.spyOn(window.localStorage.__proto__, 'setItem');
    const next = reducer(undefined, hydrateMode('dark'));
    expect(next.mode).toBe('dark');
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});
