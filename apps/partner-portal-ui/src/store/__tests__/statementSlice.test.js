import { describe, it, expect } from 'vitest';
import reducer, {
  downloadStatementThunk,
  resetStatement,
  clearStatementError
} from '../statementSlice';

/**
 * Contract lock for the statement slice.
 *
 * The CSV body is NOT stored in state (Blob is non-serializable) — only the
 * metadata { from, to, sizeBytes, downloadedAt } is recorded so the page
 * can show "Last download: ...". The Blob is handed to the caller via
 * `meta.arg.onBlob(blob)` from the page, exercised in the page test.
 */
describe('statementSlice', () => {
  it('starts idle with no last download', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual({
      status: 'idle',
      error: null,
      lastDownload: null
    });
  });

  it('marks loading when the download starts', () => {
    const state = reducer(undefined, { type: downloadStatementThunk.pending.type });
    expect(state.status).toBe('loading');
    expect(state.error).toBeNull();
  });

  it('records last-download metadata on success', () => {
    const payload = {
      from: '2026-05-01',
      to: '2026-05-31',
      sizeBytes: 12345,
      downloadedAt: '2026-06-09T11:30:00Z'
    };
    const state = reducer(undefined, {
      type: downloadStatementThunk.fulfilled.type,
      payload
    });
    expect(state.status).toBe('succeeded');
    expect(state.lastDownload).toEqual(payload);
  });

  it('captures the error on rejection via rejectWithValue', () => {
    const state = reducer(undefined, {
      type: downloadStatementThunk.rejected.type,
      payload: 'Download failed (HTTP 502)'
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('Download failed (HTTP 502)');
  });

  it('falls through to error.message when no payload is supplied', () => {
    const state = reducer(undefined, {
      type: downloadStatementThunk.rejected.type,
      error: { message: 'network' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('network');
  });

  it('clearStatementError leaves lastDownload alone', () => {
    const seeded = {
      status: 'failed',
      error: 'boom',
      lastDownload: { from: '2026-05-01', to: '2026-05-31', sizeBytes: 10, downloadedAt: 'x' }
    };
    expect(reducer(seeded, clearStatementError())).toEqual({
      status: 'failed',
      error: null,
      lastDownload: { from: '2026-05-01', to: '2026-05-31', sizeBytes: 10, downloadedAt: 'x' }
    });
  });

  it('resetStatement returns to initial state', () => {
    const seeded = {
      status: 'succeeded',
      error: null,
      lastDownload: { from: 'a', to: 'b', sizeBytes: 1, downloadedAt: 'c' }
    };
    expect(reducer(seeded, resetStatement())).toEqual({
      status: 'idle',
      error: null,
      lastDownload: null
    });
  });
});
