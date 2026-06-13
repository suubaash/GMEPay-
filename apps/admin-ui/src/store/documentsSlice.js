'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Documents slice — backs the DocumentVault component (Slice 3A.2, ADR-006).
 *
 * Manages per-partner document lists and upload state.
 *
 * State shape:
 *   byCode: { [partnerCode]: DocumentView[] }
 *   loading: { [partnerCode]: boolean }
 *   uploading: { [partnerCode]: boolean }
 *   error: { [partnerCode]: string|null }
 *
 * DocumentView shape (from GET /api/v1/admin/partners/{code}/documents):
 *   {
 *     id: string,
 *     docType: string,
 *     filename: string,
 *     contentType: string,
 *     version: number,
 *     sha256: string,
 *     expiryDate: string|null (YYYY-MM-DD),
 *     verifiedBy: string|null,
 *     verifiedAt: string|null (ISO-8601 instant),
 *     recordedAt: string (ISO-8601 instant),
 *   }
 */
const initialState = {
  /** partnerCode → DocumentView[] */
  byCode: {},
  /** partnerCode → boolean */
  loading: {},
  /** partnerCode → boolean */
  uploading: {},
  /** partnerCode → string|null */
  error: {},
};

/**
 * GET /v1/admin/partners/{partnerCode}/documents -> DocumentView[]
 */
export const fetchDocuments = createAsyncThunk(
  'documents/fetch',
  async (partnerCode) => {
    const docs = await adminApi.getDocuments(partnerCode);
    return { partnerCode, docs: Array.isArray(docs) ? docs : [] };
  },
);

/**
 * POST /v1/admin/partners/{partnerCode}/documents (multipart)
 * arg: { partnerCode, formData } where formData is a FormData with
 *   file, docType, and optionally expiryDate.
 * -> DocumentView (the newly created document)
 */
export const uploadDocument = createAsyncThunk(
  'documents/upload',
  async ({ partnerCode, formData }) => {
    const doc = await adminApi.uploadDocument(partnerCode, formData);
    return { partnerCode, doc };
  },
);

const documentsSlice = createSlice({
  name: 'documents',
  initialState,
  reducers: {
    clearDocumentError(state, action) {
      const code = action.payload;
      if (code) {
        state.error[code] = null;
      }
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchDocuments ----
      .addCase(fetchDocuments.pending, (state, action) => {
        const code = action.meta.arg;
        state.loading[code] = true;
        state.error[code] = null;
      })
      .addCase(fetchDocuments.fulfilled, (state, action) => {
        const { partnerCode, docs } = action.payload;
        state.loading[partnerCode] = false;
        state.byCode[partnerCode] = docs;
      })
      .addCase(fetchDocuments.rejected, (state, action) => {
        const code = action.meta.arg;
        state.loading[code] = false;
        state.error[code] = action.error?.message ?? 'Failed to load documents';
      })

      // ---- uploadDocument ----
      .addCase(uploadDocument.pending, (state, action) => {
        const code = action.meta.arg?.partnerCode;
        if (code) {
          state.uploading[code] = true;
          state.error[code] = null;
        }
      })
      .addCase(uploadDocument.fulfilled, (state, action) => {
        const { partnerCode, doc } = action.payload;
        state.uploading[partnerCode] = false;
        // Prepend the new doc into the list so it appears immediately.
        const existing = state.byCode[partnerCode] ?? [];
        state.byCode[partnerCode] = [doc, ...existing];
      })
      .addCase(uploadDocument.rejected, (state, action) => {
        const code = action.meta.arg?.partnerCode;
        if (code) {
          state.uploading[code] = false;
          state.error[code] = action.error?.message ?? 'Upload failed';
        }
      });
  },
});

export const { clearDocumentError } = documentsSlice.actions;
export default documentsSlice.reducer;
