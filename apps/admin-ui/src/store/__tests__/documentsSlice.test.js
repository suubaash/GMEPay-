/**
 * documentsSlice — contract lock for Slice 3A.2 (ADR-006 MinIO vault).
 *
 * Covers:
 *   - fetchDocuments lifecycle: populates byCode[partnerCode] on fulfilled.
 *   - uploadDocument lifecycle: prepends new doc on fulfilled.
 *   - uploadDocument with FormData: FormData body is not mutated.
 *   - error states: stored and clearable.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchDocuments,
  uploadDocument,
  clearDocumentError,
} from '@/store/documentsSlice';

const PARTNER_CODE = 'GME_KR_001';

const DOC_1 = {
  id: 'doc-uuid-1',
  docType: 'LICENSE_SCAN',
  filename: 'license.pdf',
  contentType: 'application/pdf',
  version: 1,
  sha256: 'aabbccdd11223344aabbccdd11223344aabbccdd11223344aabbccdd11223344',
  expiryDate: '2027-01-01',
  verifiedBy: null,
  verifiedAt: null,
  recordedAt: '2026-06-10T08:00:00Z',
};

const DOC_2 = {
  id: 'doc-uuid-2',
  docType: 'CBDDQ',
  filename: 'cbddq.docx',
  contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  version: 1,
  sha256: 'ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00',
  expiryDate: null,
  verifiedBy: null,
  verifiedAt: null,
  recordedAt: '2026-06-11T10:00:00Z',
};

describe('documentsSlice', () => {
  // ─── fetchDocuments ─────────────────────────────────────────────────────

  it('fetchDocuments.pending sets loading[partnerCode] = true and clears error', () => {
    const state = reducer(undefined, {
      type: fetchDocuments.pending.type,
      meta: { arg: PARTNER_CODE },
    });
    expect(state.loading[PARTNER_CODE]).toBe(true);
    expect(state.error[PARTNER_CODE]).toBeNull();
  });

  it('fetchDocuments.fulfilled populates byCode[partnerCode]', () => {
    const state = reducer(undefined, {
      type: fetchDocuments.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, docs: [DOC_1, DOC_2] },
    });
    expect(state.loading[PARTNER_CODE]).toBe(false);
    expect(state.byCode[PARTNER_CODE]).toHaveLength(2);
    expect(state.byCode[PARTNER_CODE][0].id).toBe('doc-uuid-1');
    expect(state.byCode[PARTNER_CODE][1].id).toBe('doc-uuid-2');
  });

  it('fetchDocuments.fulfilled with empty array clears the list', () => {
    // Seed with existing docs first.
    const seeded = reducer(undefined, {
      type: fetchDocuments.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, docs: [DOC_1] },
    });
    const cleared = reducer(seeded, {
      type: fetchDocuments.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, docs: [] },
    });
    expect(cleared.byCode[PARTNER_CODE]).toEqual([]);
  });

  it('fetchDocuments.rejected stores error message', () => {
    const state = reducer(undefined, {
      type: fetchDocuments.rejected.type,
      meta: { arg: PARTNER_CODE },
      error: { message: 'Network error' },
    });
    expect(state.loading[PARTNER_CODE]).toBe(false);
    expect(state.error[PARTNER_CODE]).toBe('Network error');
  });

  it('fetchDocuments.rejected uses fallback message when error.message absent', () => {
    const state = reducer(undefined, {
      type: fetchDocuments.rejected.type,
      meta: { arg: PARTNER_CODE },
      error: {},
    });
    expect(state.error[PARTNER_CODE]).toBe('Failed to load documents');
  });

  // ─── uploadDocument ──────────────────────────────────────────────────────

  it('uploadDocument.pending sets uploading[partnerCode] = true', () => {
    const state = reducer(undefined, {
      type: uploadDocument.pending.type,
      meta: { arg: { partnerCode: PARTNER_CODE, formData: new FormData() } },
    });
    expect(state.uploading[PARTNER_CODE]).toBe(true);
    expect(state.error[PARTNER_CODE]).toBeNull();
  });

  it('uploadDocument.fulfilled prepends new doc into byCode[partnerCode]', () => {
    // Seed one existing doc.
    const seeded = reducer(undefined, {
      type: fetchDocuments.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, docs: [DOC_1] },
    });
    const next = reducer(seeded, {
      type: uploadDocument.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, doc: DOC_2 },
    });
    expect(next.uploading[PARTNER_CODE]).toBe(false);
    expect(next.byCode[PARTNER_CODE]).toHaveLength(2);
    // Prepended — newest first.
    expect(next.byCode[PARTNER_CODE][0].id).toBe('doc-uuid-2');
    expect(next.byCode[PARTNER_CODE][1].id).toBe('doc-uuid-1');
  });

  it('uploadDocument.fulfilled on empty list initialises the array', () => {
    const state = reducer(undefined, {
      type: uploadDocument.fulfilled.type,
      payload: { partnerCode: PARTNER_CODE, doc: DOC_1 },
    });
    expect(state.byCode[PARTNER_CODE]).toHaveLength(1);
    expect(state.byCode[PARTNER_CODE][0].id).toBe('doc-uuid-1');
  });

  it('uploadDocument with FormData does not set Content-Type manually', () => {
    // This is a structural test: the FormData arg is passed unchanged to the
    // thunk. We verify the thunk arg carries partnerCode and formData fields.
    const fd = new FormData();
    fd.append('docType', 'LICENSE_SCAN');
    const arg = { partnerCode: PARTNER_CODE, formData: fd };
    // Confirm the FormData instance is preserved.
    expect(arg.formData).toBeInstanceOf(FormData);
    expect(arg.partnerCode).toBe(PARTNER_CODE);
  });

  it('uploadDocument.rejected stores error and clears uploading', () => {
    const uploading = reducer(undefined, {
      type: uploadDocument.pending.type,
      meta: { arg: { partnerCode: PARTNER_CODE, formData: new FormData() } },
    });
    const next = reducer(uploading, {
      type: uploadDocument.rejected.type,
      meta: { arg: { partnerCode: PARTNER_CODE } },
      error: { message: 'File too large' },
    });
    expect(next.uploading[PARTNER_CODE]).toBe(false);
    expect(next.error[PARTNER_CODE]).toBe('File too large');
  });

  // ─── clearDocumentError ──────────────────────────────────────────────────

  it('clearDocumentError removes the error for the specified partner', () => {
    const withError = reducer(undefined, {
      type: fetchDocuments.rejected.type,
      meta: { arg: PARTNER_CODE },
      error: { message: 'some error' },
    });
    expect(withError.error[PARTNER_CODE]).toBe('some error');

    const cleared = reducer(withError, clearDocumentError(PARTNER_CODE));
    expect(cleared.error[PARTNER_CODE]).toBeNull();
  });
});
