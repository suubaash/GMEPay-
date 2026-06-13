'use client';

import { useEffect } from 'react';
import {
  Box,
  Button,
  CircularProgress,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchActivationPreconditions } from '@/store/lifecycleSlice';

/**
 * PreconditionPanel — shows a checklist of activation preconditions for the
 * given partner.
 *
 * Calls GET /v1/admin/partners/{code}/lifecycle/preconditions on mount and
 * whenever the operator clicks "Refresh". Passing conditions are shown with
 * a green check; unmet conditions are shown with a red error icon.
 *
 * @param {object}   props
 * @param {string}   props.partnerCode  Partner to query.
 */
export function PreconditionPanel({ partnerCode }) {
  const dispatch = useAppDispatch();

  const loadingByCode = useAppSelector((s) => s.lifecycle?.loadingByCode ?? {});
  const preconditionsByCode = useAppSelector((s) => s.lifecycle?.preconditionsByCode ?? {});

  const loading = loadingByCode[partnerCode] ?? false;
  const items = preconditionsByCode[partnerCode] ?? null;

  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchActivationPreconditions(partnerCode)).catch(() => {});
    }
  }, [partnerCode, dispatch]);

  const handleRefresh = () => {
    if (partnerCode) {
      dispatch(fetchActivationPreconditions(partnerCode)).catch(() => {});
    }
  };

  return (
    <Box aria-label="precondition-panel">
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          mb: 1,
        }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          Activation checklist
        </Typography>
        <Button
          size="small"
          onClick={handleRefresh}
          disabled={loading}
          startIcon={loading ? <CircularProgress size={14} color="inherit" /> : null}
          aria-label="refresh-preconditions"
        >
          Refresh
        </Button>
      </Box>

      {items === null && loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
          <CircularProgress size={24} />
        </Box>
      )}

      {items !== null && items.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          No preconditions found.
        </Typography>
      )}

      {items !== null && items.length > 0 && (
        <List dense disablePadding>
          {items.map((item) => (
            <ListItem
              key={item.key}
              disableGutters
              aria-label={item.met ? `precondition-met-${item.key}` : `precondition-unmet-${item.key}`}
            >
              <ListItemIcon sx={{ minWidth: 32 }}>
                {item.met ? (
                  <CheckCircleOutlineIcon
                    fontSize="small"
                    color="success"
                    aria-hidden="true"
                  />
                ) : (
                  <ErrorOutlineIcon
                    fontSize="small"
                    color="error"
                    aria-hidden="true"
                  />
                )}
              </ListItemIcon>
              <ListItemText
                primary={item.description}
                primaryTypographyProps={{
                  variant: 'body2',
                  color: item.met ? 'text.primary' : 'error.main',
                }}
              />
            </ListItem>
          ))}
        </List>
      )}
    </Box>
  );
}

export default PreconditionPanel;
