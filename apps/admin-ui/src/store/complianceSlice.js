'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import {
  getComplianceOverview,
  getRegulatoryConfig,
  getPartnerKyb,
  getAuditLog,
} from '@/api/complianceApi';

/**
 * complianceSlice — backs the /compliance page (Lane 5).
 *
 * State shape:
 *   overview[]          : ComplianceRow[] from GET /v1/admin/compliance/overview
 *   overviewLoading     : boolean
 *   overviewError       : string | null
 *
 *   selectedPartnerCode : string | null   (drill-down target)
 *
 *   regulatory          : RegulatoryConfigView | null
 *   regulatoryLoading   : boolean
 *   regulatoryError     : string | null
 *
 *   kyb                 : KybView | null
 *   kybLoading          : boolean
 *   kybError            : string | null
 *
 *   auditPage           : AuditEntry[]
 *   auditMeta           : { page, size, total }
 *   auditLoading        : boolean
 *   auditError          : string | null
 *
 *   kybFilter           : string   ('ALL' | 'APPROVED' | 'PENDING' | 'REVIEW' | 'HIT')
 *   sanctionsFilter     : string   ('ALL' | 'CLEAR' | 'NEEDS_REVIEW' | 'HIT')
 *   lifecycleFilter     : string   ('ALL' | 'LIVE' | 'SUSPENDED' | 'ONBOARDING' | 'TERMINATED')
 *
 *   auditAggregate      : string   (partner code filter for audit log, '' = global)
 *   auditFrom           : string   (ISO date, '' = none)
 *   auditTo             : string   (ISO date, '' = none)
 *   auditCurrentPage    : number
 *   auditPageSize       : number
 */
const initialState = {
  // Overview table
  overview: [],
  overviewLoading: false,
  overviewError: null,

  // Drill-down
  selectedPartnerCode: null,

  // Regulatory config
  regulatory: null,
  regulatoryLoading: false,
  regulatoryError: null,

  // KYB
  kyb: null,
  kybLoading: false,
  kybError: null,

  // Audit log
  auditPage: [],
  auditMeta: { page: 0, size: 20, total: 0 },
  auditChainValid: true,
  auditLoading: false,
  auditError: null,

  // Table filters
  kybFilter: 'ALL',
  sanctionsFilter: 'ALL',
  lifecycleFilter: 'ALL',

  // Audit viewer filters
  auditAggregate: '',
  auditFrom: '',
  auditTo: '',
  auditCurrentPage: 0,
  auditPageSize: 20,
};

// ---------------------------------------------------------------------------
// Thunks
// ---------------------------------------------------------------------------

/**
 * Fetch the per-partner compliance overview.
 * GET /v1/admin/compliance/overview
 */
export const fetchComplianceOverview = createAsyncThunk(
  'compliance/fetchOverview',
  async () => {
    return getComplianceOverview();
  },
);

/**
 * Fetch regulatory config for one partner and mark it as the drill-down target.
 * GET /v1/admin/partners/{code}/regulatory
 */
export const fetchRegulatoryConfig = createAsyncThunk(
  'compliance/fetchRegulatory',
  async (partnerCode) => {
    return getRegulatoryConfig(partnerCode);
  },
);

/**
 * Fetch KYB data for one partner.
 * GET /v1/admin/partners/{code}/kyb
 */
export const fetchPartnerKyb = createAsyncThunk(
  'compliance/fetchKyb',
  async (partnerCode) => {
    return getPartnerKyb(partnerCode);
  },
);

/**
 * Fetch a page of audit log entries.
 * GET /v1/admin/audit?aggregate=&from=&to=&page=&size=
 *
 * Argument: { aggregate, from, to, page, size }
 */
export const fetchAuditLog = createAsyncThunk(
  'compliance/fetchAudit',
  async (filters) => {
    return getAuditLog(filters);
  },
);

// ---------------------------------------------------------------------------
// Slice
// ---------------------------------------------------------------------------

const complianceSlice = createSlice({
  name: 'compliance',
  initialState,
  reducers: {
    /** Open the drill-down panel for a partner. */
    selectPartner(state, action) {
      state.selectedPartnerCode = action.payload ?? null;
      // Reset panel data so stale data from the previous partner never flashes.
      state.regulatory = null;
      state.regulatoryError = null;
      state.kyb = null;
      state.kybError = null;
    },
    /** Close the drill-down panel. */
    clearSelection(state) {
      state.selectedPartnerCode = null;
      state.regulatory = null;
      state.regulatoryError = null;
      state.kyb = null;
      state.kybError = null;
    },
    // Table filters
    setKybFilter(state, action) {
      state.kybFilter = action.payload;
    },
    setSanctionsFilter(state, action) {
      state.sanctionsFilter = action.payload;
    },
    setLifecycleFilter(state, action) {
      state.lifecycleFilter = action.payload;
    },
    // Audit viewer filters
    setAuditAggregate(state, action) {
      state.auditAggregate = action.payload;
      state.auditCurrentPage = 0;
    },
    setAuditFrom(state, action) {
      state.auditFrom = action.payload;
      state.auditCurrentPage = 0;
    },
    setAuditTo(state, action) {
      state.auditTo = action.payload;
      state.auditCurrentPage = 0;
    },
    setAuditPage(state, action) {
      state.auditCurrentPage = action.payload;
    },
    clearOverviewError(state) {
      state.overviewError = null;
    },
    clearAuditError(state) {
      state.auditError = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchComplianceOverview ----
      .addCase(fetchComplianceOverview.pending, (state) => {
        state.overviewLoading = true;
        state.overviewError = null;
      })
      .addCase(fetchComplianceOverview.fulfilled, (state, action) => {
        state.overviewLoading = false;
        state.overview = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchComplianceOverview.rejected, (state, action) => {
        state.overviewLoading = false;
        state.overviewError = action.error?.message ?? 'Failed to load compliance overview';
      })

      // ---- fetchRegulatoryConfig ----
      .addCase(fetchRegulatoryConfig.pending, (state, action) => {
        state.regulatoryLoading = true;
        state.regulatoryError = null;
        state.selectedPartnerCode = action.meta.arg ?? state.selectedPartnerCode;
      })
      .addCase(fetchRegulatoryConfig.fulfilled, (state, action) => {
        state.regulatoryLoading = false;
        state.regulatory = action.payload ?? null;
      })
      .addCase(fetchRegulatoryConfig.rejected, (state, action) => {
        state.regulatoryLoading = false;
        state.regulatoryError = action.error?.message ?? 'Failed to load regulatory config';
      })

      // ---- fetchPartnerKyb ----
      .addCase(fetchPartnerKyb.pending, (state) => {
        state.kybLoading = true;
        state.kybError = null;
      })
      .addCase(fetchPartnerKyb.fulfilled, (state, action) => {
        state.kybLoading = false;
        state.kyb = action.payload ?? null;
      })
      .addCase(fetchPartnerKyb.rejected, (state, action) => {
        state.kybLoading = false;
        state.kybError = action.error?.message ?? 'Failed to load KYB data';
      })

      // ---- fetchAuditLog ----
      .addCase(fetchAuditLog.pending, (state) => {
        state.auditLoading = true;
        state.auditError = null;
      })
      .addCase(fetchAuditLog.fulfilled, (state, action) => {
        state.auditLoading = false;
        const payload = action.payload ?? {};
        state.auditPage = Array.isArray(payload.content) ? payload.content : [];
        state.auditMeta = {
          page: payload.page ?? 0,
          size: payload.size ?? 20,
          total: payload.total ?? 0,
        };
        // #78: tamper-evidence signal from the hash-chained trail (ADR-007).
        state.auditChainValid = payload.chainValid !== false;
      })
      .addCase(fetchAuditLog.rejected, (state, action) => {
        state.auditLoading = false;
        state.auditError = action.error?.message ?? 'Failed to load audit log';
      });
  },
});

export const {
  selectPartner,
  clearSelection,
  setKybFilter,
  setSanctionsFilter,
  setLifecycleFilter,
  setAuditAggregate,
  setAuditFrom,
  setAuditTo,
  setAuditPage,
  clearOverviewError,
  clearAuditError,
} = complianceSlice.actions;

export default complianceSlice.reducer;
