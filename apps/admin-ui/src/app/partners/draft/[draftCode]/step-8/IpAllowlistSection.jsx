'use client';

import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  FormControl,
  IconButton,
  InputLabel,
  List,
  ListItem,
  ListItemSecondaryAction,
  ListItemText,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import AddIcon from '@mui/icons-material/Add';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep8IpAllowlist } from '@/store/lifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/** Maximum CIDR entries per environment. */
const MAX_CIDRS = 10;

/**
 * IpAllowlistSection — manage per-environment IP CIDR allowlist entries.
 *
 * Client-side cap of 10 CIDRs per env is enforced — an Alert is shown when
 * the limit is reached and the Add button is disabled.
 *
 * Saves via PATCH /v1/admin/partners/draft/{code}/step-8/ip-allowlist.
 *
 * @param {object}   props
 * @param {string}   props.partnerCode  URL-pinned identifier.
 */
export function IpAllowlistSection({ partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const saving = useAppSelector((s) => s.lifecycle?.saving ?? false);

  const [env, setEnv] = useState('sandbox');
  const [cidrs, setCidrs] = useState([]);
  const [inputValue, setInputValue] = useState('');

  const atCap = cidrs.length >= MAX_CIDRS;

  const handleAdd = () => {
    const trimmed = inputValue.trim();
    if (!trimmed) return;
    if (atCap) return;
    if (cidrs.includes(trimmed)) {
      snackbar.warning(`${trimmed} is already in the list.`);
      return;
    }
    setCidrs((prev) => [...prev, trimmed]);
    setInputValue('');
  };

  const handleRemove = (cidr) => {
    setCidrs((prev) => prev.filter((c) => c !== cidr));
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleAdd();
    }
  };

  const handleSave = async () => {
    if (!partnerCode) {
      snackbar.error('No partner code — cannot save.');
      return;
    }
    try {
      await dispatch(patchStep8IpAllowlist({ partnerCode, body: { env, cidrs } })).unwrap();
      snackbar.success('IP allowlist saved.');
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  return (
    <Box aria-label="ip-allowlist-section">
      <Typography variant="h6" gutterBottom>
        IP Allowlist
      </Typography>

      <Stack spacing={2}>
        <FormControl size="small" sx={{ width: 200 }}>
          <InputLabel id="ip-env-label">Environment</InputLabel>
          <Select
            labelId="ip-env-label"
            value={env}
            onChange={(e) => setEnv(e.target.value)}
            label="Environment"
            inputProps={{ 'aria-label': 'ip-env-select' }}
          >
            <MenuItem value="sandbox">Sandbox</MenuItem>
            <MenuItem value="production">Production</MenuItem>
          </Select>
        </FormControl>

        {atCap && (
          <Alert severity="warning" aria-label="ip-cap-warning">
            Maximum of {MAX_CIDRS} CIDR entries reached. Remove an entry before
            adding another.
          </Alert>
        )}

        {/* CIDR input */}
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
          <TextField
            label="Add CIDR (e.g. 203.0.113.0/24)"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleKeyDown}
            size="small"
            sx={{ flex: 1 }}
            disabled={atCap}
            inputProps={{ 'aria-label': 'cidr-input' }}
          />
          <Button
            variant="outlined"
            startIcon={<AddIcon />}
            onClick={handleAdd}
            disabled={atCap || !inputValue.trim()}
            aria-label="add-cidr"
          >
            Add
          </Button>
        </Box>

        {/* CIDR list */}
        {cidrs.length > 0 && (
          <List dense disablePadding aria-label="cidr-list">
            {cidrs.map((cidr) => (
              <ListItem key={cidr} disableGutters>
                <ListItemText
                  primary={<Typography variant="body2" fontFamily="monospace">{cidr}</Typography>}
                />
                <ListItemSecondaryAction>
                  <IconButton
                    edge="end"
                    size="small"
                    aria-label={`remove-cidr-${cidr}`}
                    onClick={() => handleRemove(cidr)}
                  >
                    <DeleteOutlineIcon fontSize="small" />
                  </IconButton>
                </ListItemSecondaryAction>
              </ListItem>
            ))}
          </List>
        )}

        {cidrs.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            No CIDR entries. The partner will be allowed from any IP when this
            list is empty.
          </Typography>
        )}

        <Divider />

        <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            variant="contained"
            onClick={handleSave}
            disabled={saving}
            startIcon={saving ? <CircularProgress size={16} color="inherit" /> : null}
            aria-label="save-ip-allowlist"
          >
            Save allowlist
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

export default IpAllowlistSection;
