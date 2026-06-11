'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CloudUploadIcon from '@mui/icons-material/CloudUpload';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DownloadIcon from '@mui/icons-material/Download';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchDocuments, uploadDocument } from '@/store/documentsSlice';
import { adminApi } from '@/api/client';

/**
 * DocumentVault — grouped document list with per-group upload for
 * a single partner. Backed by MinIO (ADR-006).
 *
 * Features per ADR-006 / Slice 3A.2 spec:
 *   - Fetches all documents for partnerCode on mount.
 *   - Groups docs by docType.
 *   - Per-row: filename, version chip, sha256 (truncated 8 chars, copyable),
 *     expiry date with red highlight when < 90 days away, download link.
 *   - Upload area per doc-type group: file input (drag-and-drop optional),
 *     optional expiry-date field for doc types that require one
 *     (LICENSE_SCAN, CBDDQ).
 *   - Calls onUploaded(docId) when a document is successfully uploaded.
 *
 * @param {object}   props
 * @param {string}   props.partnerCode  Partner this vault belongs to.
 * @param {string}   [props.docType]    When provided, restricts the vault to
 *   this single doc-type group (e.g. "LICENSE_SCAN"). Defaults to showing
 *   all doc types.
 * @param {Function} [props.onUploaded] Called with docId string after upload.
 */
export default function DocumentVault({ partnerCode, docType, onUploaded }) {
  const dispatch = useAppDispatch();
  const docs = useAppSelector((s) => s.documents.byCode[partnerCode] ?? []);
  const loading = useAppSelector((s) => s.documents.loading[partnerCode] ?? false);
  const uploading = useAppSelector((s) => s.documents.uploading[partnerCode] ?? false);
  const error = useAppSelector((s) => s.documents.error[partnerCode] ?? null);

  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchDocuments(partnerCode));
    }
  }, [partnerCode, dispatch]);

  // When docType is provided, filter to just that group; otherwise show all.
  const filteredDocs = docType
    ? docs.filter((d) => d.docType === docType)
    : docs;

  // Group by docType.
  const groups = filteredDocs.reduce((acc, doc) => {
    const key = doc.docType ?? 'UNKNOWN';
    if (!acc[key]) acc[key] = [];
    acc[key].push(doc);
    return acc;
  }, {});

  // If a specific docType is requested but no docs yet, show an empty group.
  if (docType && !groups[docType]) {
    groups[docType] = [];
  }

  const groupKeys = Object.keys(groups).sort();

  return (
    <Box data-testid="document-vault">
      {loading && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ py: 1 }}>
          <CircularProgress size={16} />
          <Typography variant="body2" color="text.secondary">
            Loading documents…
          </Typography>
        </Stack>
      )}

      {error && (
        <Alert severity="error" variant="outlined" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}

      {!loading && groupKeys.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
          No documents uploaded yet.
        </Typography>
      )}

      {groupKeys.map((type, idx) => (
        <Box key={type} sx={{ mb: idx < groupKeys.length - 1 ? 3 : 0 }}>
          <Divider textAlign="left" sx={{ mb: 1.5 }}>
            <Chip
              label={type}
              size="small"
              variant="outlined"
              sx={{ fontFamily: 'monospace', fontSize: '0.7rem' }}
            />
          </Divider>

          {/* Document rows */}
          {groups[type].length === 0 && (
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ mb: 1, fontStyle: 'italic' }}
            >
              No {type} documents yet.
            </Typography>
          )}

          <Stack spacing={1} sx={{ mb: 2 }}>
            {groups[type].map((doc) => (
              <DocRow
                key={doc.id}
                doc={doc}
                partnerCode={partnerCode}
              />
            ))}
          </Stack>

          {/* Upload area for this group */}
          <UploadArea
            partnerCode={partnerCode}
            docType={type}
            uploading={uploading}
            onUploaded={onUploaded}
          />
        </Box>
      ))}
    </Box>
  );
}

// ─── Helpers ────────────────────────────────────────────────────────────────

/** Doc types that carry an expiry date the operator must supply. */
const EXPIRY_REQUIRED_TYPES = new Set(['LICENSE_SCAN', 'LICENSE', 'CBDDQ']);

/** Days threshold for "expiry soon" red highlight. */
const EXPIRY_WARNING_DAYS = 90;

function isExpiryNear(expiryDate) {
  if (!expiryDate) return false;
  const expiry = new Date(expiryDate);
  const now = new Date();
  const diffMs = expiry - now;
  const diffDays = diffMs / (1000 * 60 * 60 * 24);
  return diffDays < EXPIRY_WARNING_DAYS;
}

function truncateSha(sha) {
  if (!sha) return '—';
  return sha.length > 8 ? `${sha.slice(0, 8)}…` : sha;
}

// ─── DocRow ──────────────────────────────────────────────────────────────────

/**
 * A single document row showing filename, version, sha256, expiry, download.
 */
function DocRow({ doc, partnerCode }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    if (!doc.sha256) return;
    navigator.clipboard.writeText(doc.sha256).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }, [doc.sha256]);

  const downloadUrl = adminApi.downloadDocumentUrl(partnerCode, doc.id);
  const expiryNear = isExpiryNear(doc.expiryDate);

  return (
    <Box
      data-testid="doc-row"
      sx={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 1,
        p: 1,
        borderRadius: 1,
        border: '1px solid',
        borderColor: 'divider',
        bgcolor: 'background.paper',
      }}
    >
      {/* Filename */}
      <Typography
        variant="body2"
        sx={{ flex: '1 1 160px', fontWeight: 500, wordBreak: 'break-all' }}
      >
        {doc.filename ?? doc.id}
      </Typography>

      {/* Version chip */}
      <Chip
        label={`v${doc.version ?? 1}`}
        size="small"
        variant="outlined"
        data-testid="version-chip"
      />

      {/* SHA-256 (truncated, copyable) */}
      <Tooltip title={copied ? 'Copied!' : `SHA-256: ${doc.sha256 ?? '—'}`}>
        <Stack
          direction="row"
          alignItems="center"
          spacing={0.5}
          sx={{ cursor: 'pointer' }}
          onClick={handleCopy}
          data-testid="sha256-display"
        >
          <Typography
            variant="caption"
            sx={{ fontFamily: 'monospace', color: 'text.secondary' }}
          >
            {truncateSha(doc.sha256)}
          </Typography>
          <IconButton size="small" aria-label="copy sha256">
            <ContentCopyIcon sx={{ fontSize: 14 }} />
          </IconButton>
        </Stack>
      </Tooltip>

      {/* Expiry date */}
      {doc.expiryDate && (
        <Tooltip title={expiryNear ? 'Expiring within 90 days' : ''}>
          <Chip
            label={`Exp: ${doc.expiryDate}`}
            size="small"
            color={expiryNear ? 'error' : 'default'}
            variant={expiryNear ? 'filled' : 'outlined'}
            data-testid="expiry-chip"
          />
        </Tooltip>
      )}

      {/* Download */}
      <Tooltip title="Download">
        <IconButton
          size="small"
          component="a"
          href={downloadUrl}
          download={doc.filename}
          aria-label="download document"
          data-testid="download-link"
        >
          <DownloadIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Box>
  );
}

// ─── UploadArea ──────────────────────────────────────────────────────────────

/**
 * Upload area for a single doc-type group.
 * Supports click-to-select and drag-and-drop.
 */
function UploadArea({ partnerCode, docType, uploading, onUploaded }) {
  const dispatch = useAppDispatch();
  const fileInputRef = useRef(null);
  const [expiryDate, setExpiryDate] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const [localError, setLocalError] = useState(null);

  const needsExpiry = EXPIRY_REQUIRED_TYPES.has(docType);

  const doUpload = useCallback(
    async (file) => {
      if (!file) return;
      setLocalError(null);
      const fd = new FormData();
      fd.append('file', file);
      fd.append('docType', docType);
      if (expiryDate) {
        fd.append('expiryDate', expiryDate);
      }
      try {
        const result = await dispatch(
          uploadDocument({ partnerCode, formData: fd }),
        ).unwrap();
        if (typeof onUploaded === 'function' && result?.doc?.id) {
          onUploaded(result.doc.id);
        }
        setExpiryDate('');
      } catch (e) {
        setLocalError(e?.message ?? 'Upload failed');
      }
    },
    [dispatch, partnerCode, docType, expiryDate, onUploaded],
  );

  const handleFileChange = (e) => {
    const file = e.target.files?.[0];
    if (file) doUpload(file);
    // Reset so the same file can be re-selected after a failure.
    e.target.value = '';
  };

  const handleDragOver = (e) => {
    e.preventDefault();
    setDragOver(true);
  };
  const handleDragLeave = () => setDragOver(false);
  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) doUpload(file);
  };

  return (
    <Box>
      {localError && (
        <Alert severity="error" variant="outlined" sx={{ mb: 1 }}>
          {localError}
        </Alert>
      )}

      {needsExpiry && (
        <TextField
          label="Expiry date (YYYY-MM-DD)"
          type="date"
          size="small"
          value={expiryDate}
          onChange={(e) => setExpiryDate(e.target.value)}
          InputLabelProps={{ shrink: true }}
          sx={{ mb: 1, width: 200 }}
          inputProps={{ 'aria-label': `expiry-date-${docType}` }}
        />
      )}

      {/* Drag-drop zone / upload button */}
      <Box
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') fileInputRef.current?.click();
        }}
        aria-label={`upload-area-${docType}`}
        data-testid={`upload-area-${docType}`}
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 1,
          p: 1.5,
          border: '2px dashed',
          borderColor: dragOver ? 'primary.main' : 'divider',
          borderRadius: 1,
          cursor: 'pointer',
          bgcolor: dragOver ? 'action.hover' : 'transparent',
          transition: 'border-color 0.15s, background-color 0.15s',
          '&:hover': { borderColor: 'primary.light', bgcolor: 'action.hover' },
        }}
      >
        {uploading ? (
          <CircularProgress size={18} />
        ) : (
          <CloudUploadIcon fontSize="small" color="action" />
        )}
        <Typography variant="body2" color="text.secondary">
          {uploading
            ? 'Uploading…'
            : 'Click or drag a file to upload'}
        </Typography>

        {/* Hidden file input */}
        <Button
          component="label"
          sx={{ display: 'none' }}
          aria-hidden="true"
          tabIndex={-1}
        >
          <input
            ref={fileInputRef}
            type="file"
            hidden
            onChange={handleFileChange}
            aria-label={`file-input-${docType}`}
            data-testid={`file-input-${docType}`}
          />
        </Button>
      </Box>
    </Box>
  );
}
