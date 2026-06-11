'use client';
import * as React from 'react';
import { Provider as ReduxProvider, useSelector } from 'react-redux';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { useServerInsertedHTML } from 'next/navigation';
import { store } from '@/store';
import { getPartnerTheme } from '@/theme/theme';
import { SnackbarProvider } from '@/components/SnackbarProvider';

/**
 * Client-side providers tree.
 *
 * Emotion SSR cache wired manually (rather than via @mui/material-nextjs) so
 * the app boots without an extra optional dependency. This is the pattern
 * documented in Emotion's Next.js App Router guide.
 */
function EmotionRegistry({ children }) {
  const [{ cache, flush }] = React.useState(() => {
    const c = createCache({ key: 'mui', prepend: true });
    c.compat = true;
    const prevInsert = c.insert;
    let inserted = [];
    c.insert = (...args) => {
      const serialized = args[1];
      if (c.inserted[serialized.name] === undefined) {
        inserted.push({
          name: serialized.name,
          isGlobal: !args[0]
        });
      }
      return prevInsert(...args);
    };
    const f = () => {
      const prev = inserted;
      inserted = [];
      return prev;
    };
    return { cache: c, flush: f };
  });

  useServerInsertedHTML(() => {
    const names = flush();
    if (names.length === 0) return null;
    let styles = '';
    let dataEmotionAttribute = cache.key;
    const globals = [];
    names.forEach(({ name, isGlobal }) => {
      const style = cache.inserted[name];
      if (typeof style !== 'boolean') {
        if (isGlobal) {
          globals.push({ name, style });
        } else {
          styles += style;
          dataEmotionAttribute += ` ${name}`;
        }
      }
    });
    return (
      <>
        {globals.map(({ name, style }) => (
          <style
            key={name}
            data-emotion={`${cache.key}-global ${name}`}
            // eslint-disable-next-line react/no-danger
            dangerouslySetInnerHTML={{ __html: style }}
          />
        ))}
        {styles && (
          <style
            data-emotion={dataEmotionAttribute}
            // eslint-disable-next-line react/no-danger
            dangerouslySetInnerHTML={{ __html: styles }}
          />
        )}
      </>
    );
  });

  return <CacheProvider value={cache}>{children}</CacheProvider>;
}

/**
 * Inner provider that reads the current theme mode from Redux. Lives inside
 * `<ReduxProvider>` so `useSelector` is valid here; it switches the MUI
 * theme without re-mounting the tree.
 */
function ThemedShell({ children }) {
  const mode = useSelector((s) => s.ui?.mode ?? 'light');
  const theme = React.useMemo(() => getPartnerTheme(mode), [mode]);
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <SnackbarProvider>{children}</SnackbarProvider>
    </ThemeProvider>
  );
}

export default function Providers({ children }) {
  return (
    <ReduxProvider store={store}>
      <EmotionRegistry>
        <ThemedShell>{children}</ThemedShell>
      </EmotionRegistry>
    </ReduxProvider>
  );
}
