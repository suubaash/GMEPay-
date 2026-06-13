'use client';

import { useState } from 'react';
import { Controller, useFieldArray } from 'react-hook-form';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormHelperText,
  Grid,
  IconButton,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';

/**
 * Allow-list of (country, currency) pairs the platform currently supports.
 * Displayed as "KR / USD" for readability; values stored as separate fields.
 */
export const CORRIDOR_COUNTRY_CCY = [
  { country: 'KR', ccy: 'USD' },
  { country: 'KR', ccy: 'KRW' },
  { country: 'MN', ccy: 'USD' },
  { country: 'KH', ccy: 'USD' },
  { country: 'VN', ccy: 'VND' },
  { country: 'TH', ccy: 'THB' },
  { country: 'SG', ccy: 'SGD' },
];

/**
 * A corridor key used for deduplication in the table.
 */
function corridorKey(c) {
  return `${c.srcCountry}/${c.srcCcy}->${c.dstCountry}/${c.dstCcy}`;
}

/**
 * Default blank corridor for "Add corridor" dialog.
 */
function blankCorridor() {
  return {
    srcCountry: 'KR',
    srcCcy: 'KRW',
    dstCountry: 'VN',
    dstCcy: 'VND',
    goLiveDate: '',
    active: true,
  };
}

/**
 * Corridor dialog — used for both "Add" and "Edit".
 *
 * @param {object}   props
 * @param {boolean}  props.open
 * @param {Function} props.onClose    Called with no arg to cancel.
 * @param {Function} props.onSave     Called with the corridor object to save.
 * @param {object}   [props.initial]  Initial values when editing.
 * @param {string}   props.title      Dialog title.
 */
function CorridorDialog({ open, onClose, onSave, initial, title }) {
  const [form, setForm] = useState(() => initial ?? blankCorridor());
  const [errors, setErrors] = useState({});

  // Reset form when dialog opens (important for re-use between add/edit)
  const handleOpen = () => {
    setForm(initial ?? blankCorridor());
    setErrors({});
  };

  const set = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const validate = () => {
    const errs = {};
    if (!form.srcCountry) errs.srcCountry = 'Required';
    if (!form.srcCcy) errs.srcCcy = 'Required';
    if (!form.dstCountry) errs.dstCountry = 'Required';
    if (!form.dstCcy) errs.dstCcy = 'Required';
    if (!form.goLiveDate) errs.goLiveDate = 'Required';
    return errs;
  };

  const handleSave = () => {
    const errs = validate();
    if (Object.keys(errs).length > 0) {
      setErrors(errs);
      return;
    }
    onSave({ ...form });
    onClose();
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      TransitionProps={{ onEnter: handleOpen }}
      maxWidth="sm"
      fullWidth
      aria-label="corridor-dialog"
    >
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Grid container spacing={2}>
            {/* Source country */}
            <Grid item xs={6}>
              <FormControl fullWidth size="small" error={!!errors.srcCountry}>
                <InputLabel id="src-country-label">Source country</InputLabel>
                <Select
                  labelId="src-country-label"
                  label="Source country"
                  value={form.srcCountry}
                  onChange={(e) => set('srcCountry', e.target.value)}
                  inputProps={{ 'aria-label': 'corridor-src-country' }}
                >
                  {[...new Set(CORRIDOR_COUNTRY_CCY.map((c) => c.country))].map((c) => (
                    <MenuItem key={c} value={c}>{c}</MenuItem>
                  ))}
                </Select>
                {errors.srcCountry && <FormHelperText>{errors.srcCountry}</FormHelperText>}
              </FormControl>
            </Grid>

            {/* Source currency */}
            <Grid item xs={6}>
              <FormControl fullWidth size="small" error={!!errors.srcCcy}>
                <InputLabel id="src-ccy-label">Source currency</InputLabel>
                <Select
                  labelId="src-ccy-label"
                  label="Source currency"
                  value={form.srcCcy}
                  onChange={(e) => set('srcCcy', e.target.value)}
                  inputProps={{ 'aria-label': 'corridor-src-ccy' }}
                >
                  {CORRIDOR_COUNTRY_CCY.filter((c) => c.country === form.srcCountry).map((c) => (
                    <MenuItem key={c.ccy} value={c.ccy}>{c.ccy}</MenuItem>
                  ))}
                </Select>
                {errors.srcCcy && <FormHelperText>{errors.srcCcy}</FormHelperText>}
              </FormControl>
            </Grid>

            {/* Destination country */}
            <Grid item xs={6}>
              <FormControl fullWidth size="small" error={!!errors.dstCountry}>
                <InputLabel id="dst-country-label">Destination country</InputLabel>
                <Select
                  labelId="dst-country-label"
                  label="Destination country"
                  value={form.dstCountry}
                  onChange={(e) => set('dstCountry', e.target.value)}
                  inputProps={{ 'aria-label': 'corridor-dst-country' }}
                >
                  {[...new Set(CORRIDOR_COUNTRY_CCY.map((c) => c.country))].map((c) => (
                    <MenuItem key={c} value={c}>{c}</MenuItem>
                  ))}
                </Select>
                {errors.dstCountry && <FormHelperText>{errors.dstCountry}</FormHelperText>}
              </FormControl>
            </Grid>

            {/* Destination currency */}
            <Grid item xs={6}>
              <FormControl fullWidth size="small" error={!!errors.dstCcy}>
                <InputLabel id="dst-ccy-label">Destination currency</InputLabel>
                <Select
                  labelId="dst-ccy-label"
                  label="Destination currency"
                  value={form.dstCcy}
                  onChange={(e) => set('dstCcy', e.target.value)}
                  inputProps={{ 'aria-label': 'corridor-dst-ccy' }}
                >
                  {CORRIDOR_COUNTRY_CCY.filter((c) => c.country === form.dstCountry).map((c) => (
                    <MenuItem key={c.ccy} value={c.ccy}>{c.ccy}</MenuItem>
                  ))}
                </Select>
                {errors.dstCcy && <FormHelperText>{errors.dstCcy}</FormHelperText>}
              </FormControl>
            </Grid>
          </Grid>

          {/* Go-live date */}
          <TextField
            label="Go-live date"
            type="date"
            fullWidth
            size="small"
            value={form.goLiveDate}
            onChange={(e) => set('goLiveDate', e.target.value)}
            error={!!errors.goLiveDate}
            helperText={errors.goLiveDate ?? 'Date the corridor goes live (YYYY-MM-DD)'}
            InputLabelProps={{ shrink: true }}
            inputProps={{ 'aria-label': 'corridor-go-live-date' }}
          />

          {/* Active toggle */}
          <FormControlLabel
            control={
              <Switch
                checked={!!form.active}
                onChange={(e) => set('active', e.target.checked)}
                inputProps={{ 'aria-label': 'corridor-active' }}
              />
            }
            label="Active"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} aria-label="corridor-dialog-cancel">Cancel</Button>
        <Button variant="contained" onClick={handleSave} aria-label="corridor-dialog-save">
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Corridors Builder for Step 7 (Schemes & Corridors).
 *
 * Renders a table of configured corridors with Edit, Remove, and Toggle-active
 * per row. "Add corridor" button opens a dialog for source/destination
 * country/currency, go-live date, and active toggle.
 *
 * Country/currency pairs are restricted to the platform allow-list:
 *   KR/USD, KR/KRW, MN/USD, KH/USD, VN/VND, TH/THB, SG/SGD.
 *
 * @param {object}   props
 * @param {object}   props.control       RHF control from the parent form.
 * @param {Function} props.register      RHF register (unused directly — left for parity).
 * @param {object}   [props.errors]      RHF errors scoped to corridors array.
 */
export default function CorridorsBuilder({ control, register, errors }) {
  const { fields, append, remove, update } = useFieldArray({
    control,
    name: 'corridors',
  });

  const [addOpen, setAddOpen] = useState(false);
  const [editIndex, setEditIndex] = useState(null);

  const handleAdd = (corridor) => {
    append(corridor);
  };

  const handleEdit = (corridor) => {
    if (editIndex !== null) {
      update(editIndex, corridor);
      setEditIndex(null);
    }
  };

  const handleToggleActive = (index) => {
    const current = fields[index];
    update(index, { ...current, active: !current.active });
  };

  return (
    <Box aria-label="corridors-builder-section">
      <Stack spacing={2}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <Box>
            <Typography variant="h6" gutterBottom>
              Corridors
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Define the source/destination country-currency pairs this partner
              is licensed to operate. Corridors can be toggled active/inactive
              after go-live without removing the configuration.
            </Typography>
          </Box>
          <Button
            size="small"
            startIcon={<AddIcon />}
            onClick={() => setAddOpen(true)}
            aria-label="add-corridor"
            variant="outlined"
          >
            Add corridor
          </Button>
        </Box>

        {fields.length === 0 ? (
          <Alert severity="info" aria-label="no-corridors-info">
            No corridors configured. Add at least one corridor before saving.
          </Alert>
        ) : (
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="corridors-table">
              <TableHead>
                <TableRow>
                  <TableCell>Source</TableCell>
                  <TableCell>Destination</TableCell>
                  <TableCell>Go-live</TableCell>
                  <TableCell>Active</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {fields.map((field, index) => (
                  <TableRow key={field.id} hover>
                    <TableCell>
                      <Typography variant="body2">
                        {field.srcCountry} / {field.srcCcy}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">
                        {field.dstCountry} / {field.dstCcy}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{field.goLiveDate}</Typography>
                    </TableCell>
                    <TableCell>
                      <Switch
                        checked={!!field.active}
                        onChange={() => handleToggleActive(index)}
                        size="small"
                        inputProps={{ 'aria-label': `corridor-active-toggle-${index}` }}
                      />
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5}>
                        <Tooltip title="Edit">
                          <IconButton
                            size="small"
                            onClick={() => setEditIndex(index)}
                            aria-label={`edit-corridor-${index}`}
                          >
                            <EditIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title="Remove">
                          <IconButton
                            size="small"
                            onClick={() => remove(index)}
                            aria-label={`remove-corridor-${index}`}
                            color="error"
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Stack>

      {/* Add corridor dialog */}
      <CorridorDialog
        open={addOpen}
        onClose={() => setAddOpen(false)}
        onSave={handleAdd}
        title="Add corridor"
      />

      {/* Edit corridor dialog */}
      {editIndex !== null && (
        <CorridorDialog
          open
          onClose={() => setEditIndex(null)}
          onSave={handleEdit}
          initial={{ ...fields[editIndex] }}
          title="Edit corridor"
        />
      )}
    </Box>
  );
}
