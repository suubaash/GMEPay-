'use client';

import { useState } from 'react';
import {
  Box,
  Button,
  CircularProgress,
  Divider,
  FormControl,
  InputLabel,
  MenuItem,
  OutlinedInput,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep8Regulatory } from '@/store/lifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/** Country options for PIPA jurisdiction multi-select (representative subset). */
const COUNTRY_OPTIONS = [
  'KR', 'US', 'JP', 'CN', 'GB', 'DE', 'SG', 'AU', 'HK', 'PH',
  'VN', 'TH', 'IN', 'MY', 'ID', 'NZ', 'CA', 'FR', 'IT', 'NL',
];

const BOK_FX_REPORTING_CATEGORIES = [
  'OVERSEAS_REMITTANCE',
  'IMPORT_PAYMENT',
  'EXPORT_RECEIPT',
  'CAPITAL_TRANSACTION',
  'OTHER',
];

const BOK_REMITTER_TYPES = [
  'INDIVIDUAL',
  'CORPORATE',
  'FINANCIAL_INSTITUTION',
  'GOVERNMENT',
];

const HOMETAX_VAT_TREATMENTS = [
  'ZERO_RATED',
  'EXEMPT',
  'STANDARD',
];

const TRAVEL_RULE_PROTOCOLS = [
  'TRISA',
  'OPENVASP',
  'VERISCOPE',
  'NONE',
];

const ITEM_HEIGHT = 48;
const ITEM_PADDING_TOP = 8;
const MenuProps = {
  PaperProps: {
    style: { maxHeight: ITEM_HEIGHT * 4.5 + ITEM_PADDING_TOP, width: 250 },
  },
};

/**
 * RegulatorySection — editable form for BOK / Hometax / KoFIU / PIPA /
 * Travel Rule regulatory settings.
 *
 * Saves via PATCH /v1/admin/partners/draft/{code}/step-8/regulatory.
 *
 * NOTE on KoFIU BigDecimal fields (ctrThresholdKrw, thresholdKrw):
 * These are stored and transmitted as strings. They are NEVER cast to
 * Number — the TextField value is kept as a string throughout.
 *
 * @param {object}   props
 * @param {object}   props.draft        PartnerView (read existing values from).
 * @param {string}   props.partnerCode  URL-pinned identifier.
 */
export function RegulatorySection({ draft, partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const saving = useAppSelector((s) => s.lifecycle?.saving ?? false);

  const reg = draft?.regulatory ?? {};

  // BOK
  const [txnCode, setTxnCode] = useState(reg?.bok?.txnCode ?? '');
  const [fxReportingCategory, setFxReportingCategory] = useState(reg?.bok?.fxReportingCategory ?? '');
  const [remitterType, setRemitterType] = useState(reg?.bok?.remitterType ?? '');

  // Hometax
  const [hometaxIssuerCertId, setHometaxIssuerCertId] = useState(reg?.hometax?.hometaxIssuerCertId ?? '');
  const [vatTreatment, setVatTreatment] = useState(reg?.hometax?.vatTreatment ?? '');

  // KoFIU
  const [kofiuEntityId, setKofiuEntityId] = useState(reg?.kofiu?.kofiuEntityId ?? '');
  // BigDecimal stored as string — NOT cast to Number
  const [ctrThresholdKrw, setCtrThresholdKrw] = useState(reg?.kofiu?.ctrThresholdKrw ?? '');

  // PIPA
  const [pipaJurisdictionAllowlist, setPipaJurisdictionAllowlist] = useState(
    reg?.pipa?.pipaJurisdictionAllowlist ?? [],
  );

  // Travel Rule
  const [travelRuleProtocol, setTravelRuleProtocol] = useState(reg?.travelRule?.protocol ?? '');
  const [travelRuleEndpointUrl, setTravelRuleEndpointUrl] = useState(reg?.travelRule?.endpointUrl ?? '');
  // BigDecimal stored as string — NOT cast to Number
  const [travelRuleThresholdKrw, setTravelRuleThresholdKrw] = useState(reg?.travelRule?.thresholdKrw ?? '');

  const handleSave = async () => {
    if (!partnerCode) {
      snackbar.error('No partner code — cannot save.');
      return;
    }

    const body = {
      bok: {
        txnCode: txnCode.trim() || null,
        fxReportingCategory: fxReportingCategory || null,
        remitterType: remitterType || null,
      },
      hometax: {
        hometaxIssuerCertId: hometaxIssuerCertId.trim() || null,
        vatTreatment: vatTreatment || null,
      },
      kofiu: {
        kofiuEntityId: kofiuEntityId.trim() || null,
        // Pass as string — BigDecimal on the wire
        ctrThresholdKrw: ctrThresholdKrw.trim() || null,
      },
      pipa: {
        pipaJurisdictionAllowlist: pipaJurisdictionAllowlist,
      },
      travelRule: {
        protocol: travelRuleProtocol || null,
        endpointUrl: travelRuleEndpointUrl.trim() || null,
        // Pass as string — BigDecimal on the wire
        thresholdKrw: travelRuleThresholdKrw.trim() || null,
      },
    };

    try {
      await dispatch(patchStep8Regulatory({ partnerCode, body })).unwrap();
      snackbar.success('Regulatory settings saved.');
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  return (
    <Box aria-label="regulatory-section" data-testid="regulatory-section">
      <Typography variant="h6" gutterBottom>
        Regulatory &amp; Compliance
      </Typography>

      <Stack spacing={3}>
        {/* ── BOK ──────────────────────────────────────────────────────── */}
        <Box>
          <Typography variant="subtitle2" gutterBottom>
            Bank of Korea (BOK)
          </Typography>
          <Stack spacing={2}>
            <TextField
              label="Transaction code"
              value={txnCode}
              onChange={(e) => setTxnCode(e.target.value)}
              size="small"
              fullWidth
              inputProps={{ 'aria-label': 'bok-txn-code' }}
            />
            <FormControl size="small" fullWidth>
              <InputLabel id="bok-fx-cat-label">FX reporting category</InputLabel>
              <Select
                labelId="bok-fx-cat-label"
                value={fxReportingCategory}
                onChange={(e) => setFxReportingCategory(e.target.value)}
                label="FX reporting category"
                inputProps={{ 'aria-label': 'bok-fx-reporting-category' }}
              >
                <MenuItem value=""><em>— select —</em></MenuItem>
                {BOK_FX_REPORTING_CATEGORIES.map((v) => (
                  <MenuItem key={v} value={v}>{v}</MenuItem>
                ))}
              </Select>
            </FormControl>
            <FormControl size="small" fullWidth>
              <InputLabel id="bok-remitter-label">Remitter type</InputLabel>
              <Select
                labelId="bok-remitter-label"
                value={remitterType}
                onChange={(e) => setRemitterType(e.target.value)}
                label="Remitter type"
                inputProps={{ 'aria-label': 'bok-remitter-type' }}
              >
                <MenuItem value=""><em>— select —</em></MenuItem>
                {BOK_REMITTER_TYPES.map((v) => (
                  <MenuItem key={v} value={v}>{v}</MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>
        </Box>

        <Divider />

        {/* ── Hometax ──────────────────────────────────────────────────── */}
        <Box>
          <Typography variant="subtitle2" gutterBottom>
            Hometax
          </Typography>
          <Stack spacing={2}>
            <TextField
              label="Hometax issuer cert ID"
              value={hometaxIssuerCertId}
              onChange={(e) => setHometaxIssuerCertId(e.target.value)}
              size="small"
              fullWidth
              inputProps={{ 'aria-label': 'hometax-issuer-cert-id' }}
            />
            <FormControl size="small" fullWidth>
              <InputLabel id="vat-treatment-label">VAT treatment</InputLabel>
              <Select
                labelId="vat-treatment-label"
                value={vatTreatment}
                onChange={(e) => setVatTreatment(e.target.value)}
                label="VAT treatment"
                inputProps={{ 'aria-label': 'vat-treatment' }}
              >
                <MenuItem value=""><em>— select —</em></MenuItem>
                {HOMETAX_VAT_TREATMENTS.map((v) => (
                  <MenuItem key={v} value={v}>{v}</MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>
        </Box>

        <Divider />

        {/* ── KoFIU ────────────────────────────────────────────────────── */}
        <Box>
          <Typography variant="subtitle2" gutterBottom>
            KoFIU
          </Typography>
          <Stack spacing={2}>
            <TextField
              label="KoFIU entity ID"
              value={kofiuEntityId}
              onChange={(e) => setKofiuEntityId(e.target.value)}
              size="small"
              fullWidth
              inputProps={{ 'aria-label': 'kofiu-entity-id' }}
            />
            <TextField
              label="CTR threshold (KRW — decimal string)"
              value={ctrThresholdKrw}
              onChange={(e) => setCtrThresholdKrw(e.target.value)}
              size="small"
              fullWidth
              helperText="BigDecimal as string, e.g. 10000000"
              inputProps={{ 'aria-label': 'ctr-threshold-krw' }}
            />
          </Stack>
        </Box>

        <Divider />

        {/* ── PIPA ─────────────────────────────────────────────────────── */}
        <Box>
          <Typography variant="subtitle2" gutterBottom>
            PIPA — Jurisdiction allowlist
          </Typography>
          <FormControl size="small" fullWidth>
            <InputLabel id="pipa-label">Countries</InputLabel>
            <Select
              labelId="pipa-label"
              multiple
              value={pipaJurisdictionAllowlist}
              onChange={(e) => {
                const val = e.target.value;
                setPipaJurisdictionAllowlist(typeof val === 'string' ? val.split(',') : val);
              }}
              input={<OutlinedInput label="Countries" />}
              MenuProps={MenuProps}
              inputProps={{ 'aria-label': 'pipa-jurisdiction-allowlist' }}
            >
              {COUNTRY_OPTIONS.map((c) => (
                <MenuItem key={c} value={c}>{c}</MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>

        <Divider />

        {/* ── Travel Rule ──────────────────────────────────────────────── */}
        <Box>
          <Typography variant="subtitle2" gutterBottom>
            Travel Rule
          </Typography>
          <Stack spacing={2}>
            <FormControl size="small" fullWidth>
              <InputLabel id="tr-protocol-label">Protocol</InputLabel>
              <Select
                labelId="tr-protocol-label"
                value={travelRuleProtocol}
                onChange={(e) => setTravelRuleProtocol(e.target.value)}
                label="Protocol"
                inputProps={{ 'aria-label': 'travel-rule-protocol' }}
              >
                <MenuItem value=""><em>— select —</em></MenuItem>
                {TRAVEL_RULE_PROTOCOLS.map((v) => (
                  <MenuItem key={v} value={v}>{v}</MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField
              label="Endpoint URL"
              value={travelRuleEndpointUrl}
              onChange={(e) => setTravelRuleEndpointUrl(e.target.value)}
              size="small"
              fullWidth
              inputProps={{ 'aria-label': 'travel-rule-endpoint-url' }}
            />
            <TextField
              label="Threshold (KRW — decimal string)"
              value={travelRuleThresholdKrw}
              onChange={(e) => setTravelRuleThresholdKrw(e.target.value)}
              size="small"
              fullWidth
              helperText="BigDecimal as string, e.g. 1000000"
              inputProps={{ 'aria-label': 'travel-rule-threshold-krw' }}
            />
          </Stack>
        </Box>

        <Divider />

        <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            variant="contained"
            onClick={handleSave}
            disabled={saving}
            startIcon={saving ? <CircularProgress size={16} color="inherit" /> : null}
            aria-label="save-regulatory"
          >
            Save regulatory
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

export default RegulatorySection;
