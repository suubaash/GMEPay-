'use client';

import { useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
} from '@mui/material';
import EditIcon from '@mui/icons-material/Edit';
import RegulatorySection from '../draft/[draftCode]/step-8/RegulatorySection';

/**
 * RegulatorySettingsTab — read-only summary of the regulatory config for an
 * activated partner, plus an "Edit" button that opens RegulatorySection in an
 * edit dialog.
 *
 * RegulatorySection is imported from step-8 (NOT duplicated).
 *
 * Props:
 *   regulatory: RegulatoryView | null
 *     { bok, hometax, kofiu }
 *   onSaved: () => void  — called after an edit is persisted (trigger parent reload)
 */
export default function RegulatorySettingsTab({ regulatory, onSaved }) {
  const [editOpen, setEditOpen] = useState(false);

  const handleEditClose = () => {
    setEditOpen(false);
  };

  const handleSaved = () => {
    setEditOpen(false);
    onSaved?.();
  };

  return (
    <Box data-testid="regulatory-settings-tab">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
        <Typography variant="subtitle1">Regulatory configuration</Typography>
        <Button
          variant="outlined"
          startIcon={<EditIcon />}
          onClick={() => setEditOpen(true)}
          data-testid="edit-regulatory-btn"
          size="small"
        >
          Edit
        </Button>
      </Box>

      {!regulatory && (
        <Typography variant="body2" color="text.secondary">
          No regulatory configuration loaded.
        </Typography>
      )}

      {regulatory && (
        <RegulatorySection regulatory={regulatory} />
      )}

      {/* Edit dialog — reuses RegulatorySection in read/write context */}
      <Dialog
        open={editOpen}
        onClose={handleEditClose}
        aria-labelledby="edit-regulatory-title"
        maxWidth="md"
        fullWidth
        data-testid="edit-regulatory-dialog"
      >
        <DialogTitle id="edit-regulatory-title">
          Edit regulatory configuration
        </DialogTitle>
        <DialogContent>
          <Box sx={{ mt: 1 }}>
            <RegulatorySection regulatory={regulatory} />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleEditClose}>Cancel</Button>
          <Button variant="contained" onClick={handleSaved} data-testid="save-regulatory-btn">
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
