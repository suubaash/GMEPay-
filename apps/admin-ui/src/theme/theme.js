'use client';

import { createTheme } from '@mui/material/styles';

/**
 * GMEPay+ Ops/Admin Portal MUI theme — premium GME brand.
 *
 * Brand: GME red on white. Light mode is mostly white/near-white surfaces with
 * red accents, soft layered shadows, 3D hover-lift on cards, gradient red
 * buttons, an animated red-accented sidebar, a frosted-glass app bar, and a
 * gentle fade-up entrance. Dark mode mirrors the brand on graphite surfaces.
 *
 * Selected at runtime via {@link buildTheme}; ThemeRegistry reads state.ui.mode.
 */

// ---- GME brand reds ----
const RED = '#E11B2E';        // GME red (primary)
const RED_DARK = '#A8101E';
const RED_LIGHT = '#FF536A';
const RED_GRAD = 'linear-gradient(135deg,#FF4D63 0%,#E11B2E 55%,#C2122180 140%)';
const RED_GRAD_HOVER = 'linear-gradient(135deg,#FF3A52 0%,#D2152680 130%)';

// soft, layered, premium shadows (light)
const SHADOW_SM = '0 1px 2px rgba(16,24,40,.05), 0 1px 3px rgba(16,24,40,.04)';
const SHADOW_MD = '0 1px 2px rgba(16,24,40,.04), 0 8px 24px rgba(16,24,40,.07)';
const SHADOW_LIFT = '0 16px 40px rgba(225,27,46,.16), 0 8px 18px rgba(16,24,40,.10)';

function paletteFor(mode) {
  if (mode === 'dark') {
    return {
      mode: 'dark',
      primary: { main: '#FF4D63', light: '#FF8593', dark: RED, contrastText: '#1A0306' },
      secondary: { main: '#A1A1AA', light: '#D4D4D8', dark: '#71717A', contrastText: '#0B0B0E' },
      background: { default: '#0C0C10', paper: '#16161C' },
      text: { primary: '#F2F2F5', secondary: '#A1A1AA' },
      divider: 'rgba(255,255,255,0.08)',
      success: { main: '#34D399' }, warning: { main: '#FBBF24' }, error: { main: '#F87171' }, info: { main: '#60A5FA' },
    };
  }
  return {
    mode: 'light',
    primary: { main: RED, light: RED_LIGHT, dark: RED_DARK, contrastText: '#FFFFFF' },
    secondary: { main: '#1F2430', light: '#3A4150', dark: '#11141C', contrastText: '#FFFFFF' },
    background: { default: '#F5F6F9', paper: '#FFFFFF' },
    text: { primary: '#15171C', secondary: '#5A6273' },
    divider: '#ECEDF1',
    success: { main: '#0E9F6E' }, warning: { main: '#E08A00' }, error: { main: '#D7263D' }, info: { main: '#2563EB' },
  };
}

export function buildTheme(mode) {
  const isDark = mode === 'dark';
  const p = paletteFor(isDark ? 'dark' : 'light');

  return createTheme({
    palette: p,
    typography: {
      fontFamily:
        '"Inter", "SF Pro Display", system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
      h1: { fontSize: '2.1rem', fontWeight: 800, lineHeight: 1.15, letterSpacing: '-0.025em' },
      h2: { fontSize: '1.6rem', fontWeight: 800, lineHeight: 1.18, letterSpacing: '-0.02em' },
      h3: { fontSize: '1.55rem', fontWeight: 800, lineHeight: 1.2, letterSpacing: '-0.02em' },
      h4: { fontSize: '1.15rem', fontWeight: 700, lineHeight: 1.25, letterSpacing: '-0.01em' },
      h6: { fontWeight: 700, letterSpacing: '-0.01em' },
      subtitle2: { fontWeight: 700, letterSpacing: '0.02em' },
      button: { fontWeight: 600, letterSpacing: 0 },
      body2: { letterSpacing: '0.01em' },
    },
    shape: { borderRadius: 14 },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          '*': { boxSizing: 'border-box' },
          'html, body, #__next': { height: '100%' },
          body: {
            backgroundColor: p.background.default,
            backgroundImage: isDark
              ? 'radial-gradient(900px 500px at 100% -8%, rgba(255,77,99,.12), transparent 60%), radial-gradient(700px 500px at -5% 110%, rgba(255,77,99,.06), transparent 55%)'
              : 'radial-gradient(1100px 560px at 100% -10%, rgba(225,27,46,.07), transparent 58%), radial-gradient(800px 520px at -8% 112%, rgba(225,27,46,.045), transparent 55%), linear-gradient(180deg,#FCFCFE 0%,#F5F6F9 100%)',
            backgroundAttachment: 'fixed',
            WebkitFontSmoothing: 'antialiased',
            MozOsxFontSmoothing: 'grayscale',
          },
          '::selection': { background: 'rgba(225,27,46,.20)' },
          '*::-webkit-scrollbar': { width: 11, height: 11 },
          '*::-webkit-scrollbar-thumb': {
            background: isDark ? 'rgba(255,255,255,.16)' : 'rgba(90,98,115,.32)',
            borderRadius: 9, border: '3px solid transparent', backgroundClip: 'content-box',
          },
          '*::-webkit-scrollbar-thumb:hover': { background: 'rgba(225,27,46,.55)', backgroundClip: 'content-box' },
          '@keyframes gmeFadeUp': { from: { opacity: 0, transform: 'translateY(12px)' }, to: { opacity: 1, transform: 'none' } },
          '@keyframes gmePop': { from: { opacity: 0, transform: 'scale(.97)' }, to: { opacity: 1, transform: 'none' } },
        },
      },
      MuiCard: {
        defaultProps: { elevation: 0 },
        styleOverrides: {
          root: {
            borderRadius: 18,
            border: `1px solid ${p.divider}`,
            backgroundImage: isDark
              ? 'linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,0))'
              : 'linear-gradient(180deg,#FFFFFF, #FCFCFE)',
            boxShadow: SHADOW_MD,
            transition: 'transform .26s cubic-bezier(.2,.7,.2,1), box-shadow .26s cubic-bezier(.2,.7,.2,1), border-color .26s',
            '&:hover': {
              transform: 'translateY(-4px)',
              boxShadow: SHADOW_LIFT,
              borderColor: 'rgba(225,27,46,.30)',
            },
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: { backgroundImage: 'none' },
          rounded: { borderRadius: 16 },
          outlined: { borderColor: p.divider },
          elevation1: { boxShadow: SHADOW_SM },
          elevation2: { boxShadow: SHADOW_MD },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: {
            borderRadius: 11,
            textTransform: 'none',
            fontWeight: 600,
            padding: '8px 18px',
            transition: 'transform .18s ease, box-shadow .18s ease, background .18s ease',
            '&:hover': { transform: 'translateY(-1px)' },
            '&:active': { transform: 'translateY(0)' },
          },
          containedPrimary: {
            background: RED_GRAD,
            boxShadow: '0 6px 16px rgba(225,27,46,.30)',
            '&:hover': { background: RED_GRAD_HOVER, boxShadow: '0 12px 26px rgba(225,27,46,.42)' },
          },
          outlinedPrimary: {
            borderColor: 'rgba(225,27,46,.45)',
            '&:hover': { borderColor: RED, background: 'rgba(225,27,46,.06)' },
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { borderRadius: 9, fontWeight: 600 },
          filledPrimary: { background: RED_GRAD, color: '#fff' },
        },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: {
            border: 'none',
            borderRight: `1px solid ${p.divider}`,
            backgroundColor: p.background.paper,
            backgroundImage: isDark
              ? 'linear-gradient(180deg,#191920,#121217)'
              : 'linear-gradient(180deg,#FFFFFF, #FBFBFD)',
            boxShadow: isDark ? 'none' : '2px 0 18px rgba(16,24,40,.04)',
          },
        },
      },
      MuiListItemButton: {
        styleOverrides: {
          root: {
            borderRadius: 11,
            margin: '2px 10px',
            paddingTop: 8,
            paddingBottom: 8,
            transition: 'background .2s ease, color .2s ease, transform .2s ease',
            '&:hover': { backgroundColor: 'rgba(225,27,46,.07)', transform: 'translateX(3px)' },
            '&.Mui-selected': {
              backgroundColor: 'rgba(225,27,46,.10)',
              color: RED,
              fontWeight: 700,
              position: 'relative',
              '&:hover': { backgroundColor: 'rgba(225,27,46,.14)' },
              '&::before': {
                content: '""', position: 'absolute', left: 0, top: 8, bottom: 8, width: 3,
                borderRadius: 3, background: RED_GRAD,
              },
              '& .MuiListItemIcon-root': { color: RED },
              '& .MuiListItemText-primary': { fontWeight: 700 },
            },
          },
        },
      },
      MuiListItemIcon: { styleOverrides: { root: { minWidth: 38, color: 'inherit', transition: 'color .2s ease' } } },
      MuiListItemText: { styleOverrides: { primary: { fontSize: '.9rem', fontWeight: 500 } } },
      MuiTableHead: {
        styleOverrides: {
          root: {
            '& .MuiTableCell-head': {
              backgroundColor: isDark ? 'rgba(255,255,255,.03)' : '#FAFAFC',
              color: p.text.secondary,
              fontWeight: 700,
              fontSize: '.72rem',
              letterSpacing: '.05em',
              textTransform: 'uppercase',
              borderBottom: `1px solid ${p.divider}`,
            },
          },
        },
      },
      MuiTableRow: {
        styleOverrides: {
          root: { transition: 'background .15s ease', '&:hover': { backgroundColor: 'rgba(225,27,46,.035)' } },
        },
      },
      MuiTableCell: { styleOverrides: { root: { borderBottom: `1px solid ${p.divider}` } } },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            borderRadius: 11,
            transition: 'box-shadow .2s ease',
            '&.Mui-focused': { boxShadow: '0 0 0 3px rgba(225,27,46,.14)' },
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: RED, borderWidth: 1.5 },
          },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { borderRadius: 8, fontSize: '.72rem', background: 'rgba(21,23,28,.94)', padding: '6px 10px' },
        },
      },
      MuiAvatar: { styleOverrides: { root: { fontWeight: 700 } } },
      MuiDivider: { styleOverrides: { root: { borderColor: p.divider } } },
      MuiLinearProgress: { styleOverrides: { root: { borderRadius: 6, height: 8 } } },
    },
  });
}

/**
 * @deprecated Prefer {@link buildTheme}(mode). Retained for tests importing
 * `theme` directly; defaults to light.
 */
export const theme = buildTheme('light');
