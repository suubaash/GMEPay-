/**
 * Vitest unit tests for reportsSlice.
 *
 *  1. fetchReports.fulfilled populates items.
 *  2. fetchReports.rejected sets error.
 *  3. triggerGenerate.fulfilled prepends the new run.
 *  4. triggerGenerate.rejected sets generateError.
 *  5. downloadReportRun.pending/fulfilled toggles downloading flag.
 *  6. setFilters merges partial patch into filters.
 *  7. clearError / clearGenerateError clear the respective fields.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { configureStore } from '@reduxjs/toolkit';
import reportsReducer, {
  fetchReports,
  triggerGenerate,
  downloadReportRun,
  setFilters,
  clearError,
  clearGenerateError,
} from '@/store/reportsSlice';

// Mock reportsApi so no real network calls happen.
const mockListReports = vi.fn();
const mockGenerateReport = vi.fn();
const mockDownloadReport = vi.fn();

vi.mock('@/api/reportsApi', () => ({
  listReports: (...args) => mockListReports(...args),
  generateReport: (...args) => mockGenerateReport(...args),
  downloadReport: (...args) => mockDownloadReport(...args),
  FIXTURE_REPORT_RUNS: [],
}));

function makeStore() {
  return configureStore({ reducer: { reports: reportsReducer } });
}

const RUN_A = {
  id: 'rpt-001',
  type: 'BOK_FX1014',
  period: '2025-05',
  status: 'SUBMITTED',
  recordCount: '1428',
  generatedAt: '2025-06-01T01:30:00Z',
  downloadUrl: null,
};

const RUN_B = {
  id: 'rpt-002',
  type: 'BOK_FX1015',
  period: '2025-05',
  status: 'GENERATED',
  recordCount: '312',
  generatedAt: '2025-06-01T02:15:00Z',
  downloadUrl: '/v1/admin/reports/rpt-002/download',
};

describe('reportsSlice', () => {
  beforeEach(() => {
    mockListReports.mockReset();
    mockGenerateReport.mockReset();
    mockDownloadReport.mockReset();
  });

  // 1. fetchReports.fulfilled populates items
  it('fetchReports.fulfilled stores items', async () => {
    mockListReports.mockResolvedValue([RUN_A, RUN_B]);
    const store = makeStore();

    await store.dispatch(fetchReports({}));

    const { items, loading, error } = store.getState().reports;
    expect(loading).toBe(false);
    expect(error).toBeNull();
    expect(items).toHaveLength(2);
    expect(items[0].id).toBe('rpt-001');
    expect(items[1].id).toBe('rpt-002');
  });

  // 2. fetchReports.rejected sets error
  it('fetchReports.rejected sets error message', async () => {
    mockListReports.mockRejectedValue(new Error('BFF down'));
    const store = makeStore();

    await store.dispatch(fetchReports({}));

    const { loading, error } = store.getState().reports;
    expect(loading).toBe(false);
    expect(error).toBe('BFF down');
  });

  // 3. triggerGenerate.fulfilled prepends the new run
  it('triggerGenerate.fulfilled prepends the new run to items', async () => {
    mockListReports.mockResolvedValue([RUN_A, RUN_B]);
    const store = makeStore();
    await store.dispatch(fetchReports({}));

    const newRun = {
      id: 'rpt-new',
      type: 'BOK_FX1014',
      period: '2025-06',
      status: 'PENDING',
      recordCount: '0',
      generatedAt: new Date().toISOString(),
      downloadUrl: null,
    };
    mockGenerateReport.mockResolvedValue(newRun);

    await store.dispatch(triggerGenerate({ type: 'BOK_FX1014', period: '2025-06' }));

    const { items, generating, generateError } = store.getState().reports;
    expect(generating).toBe(false);
    expect(generateError).toBeNull();
    expect(items[0].id).toBe('rpt-new');
    expect(items).toHaveLength(3);
  });

  // 4. triggerGenerate.rejected sets generateError
  it('triggerGenerate.rejected sets generateError', async () => {
    mockGenerateReport.mockRejectedValue(new Error('generate failed'));
    const store = makeStore();

    await store.dispatch(triggerGenerate({ type: 'KOFIU_CTR' }));

    const { generating, generateError } = store.getState().reports;
    expect(generating).toBe(false);
    expect(generateError).toBe('generate failed');
  });

  // 5. downloadReportRun.pending sets downloading[id], .fulfilled clears it
  it('downloadReportRun toggles downloading flag by id', async () => {
    const fakeBlob = new Blob(['data'], { type: 'application/zip' });
    mockDownloadReport.mockResolvedValue(fakeBlob);

    // Stub DOM methods that downloadReportRun uses.
    URL.createObjectURL = vi.fn().mockReturnValue('blob:mock');
    URL.revokeObjectURL = vi.fn();
    const origCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      if (tag === 'a') {
        const a = origCreate('a');
        a.click = vi.fn();
        return a;
      }
      return origCreate(tag);
    });

    const store = makeStore();
    const promise = store.dispatch(
      downloadReportRun({ id: 'rpt-002', filename: 'test.zip' }),
    );

    // After dispatch, pending state sets the flag
    expect(store.getState().reports.downloading['rpt-002']).toBe(true);

    await promise;

    // After fulfil, flag is cleared
    expect(store.getState().reports.downloading['rpt-002']).toBeUndefined();

    vi.restoreAllMocks();
  });

  // 6. setFilters merges patch
  it('setFilters merges partial patch into filters', () => {
    const store = makeStore();
    store.dispatch(setFilters({ type: 'BOK_FX1014' }));

    const { filters } = store.getState().reports;
    expect(filters.type).toBe('BOK_FX1014');
    expect(filters.from).toBe('');
    expect(filters.to).toBe('');

    store.dispatch(setFilters({ from: '2025-01-01', to: '2025-06-30' }));
    const updated = store.getState().reports.filters;
    expect(updated.type).toBe('BOK_FX1014');
    expect(updated.from).toBe('2025-01-01');
    expect(updated.to).toBe('2025-06-30');
  });

  // 7a. clearError clears the error field
  it('clearError clears error', async () => {
    mockListReports.mockRejectedValue(new Error('oops'));
    const store = makeStore();
    await store.dispatch(fetchReports({}));
    expect(store.getState().reports.error).toBe('oops');

    store.dispatch(clearError());
    expect(store.getState().reports.error).toBeNull();
  });

  // 7b. clearGenerateError clears the generateError field
  it('clearGenerateError clears generateError', async () => {
    mockGenerateReport.mockRejectedValue(new Error('gen-err'));
    const store = makeStore();
    await store.dispatch(triggerGenerate({ type: 'KOFIU_STR' }));
    expect(store.getState().reports.generateError).toBe('gen-err');

    store.dispatch(clearGenerateError());
    expect(store.getState().reports.generateError).toBeNull();
  });
});
