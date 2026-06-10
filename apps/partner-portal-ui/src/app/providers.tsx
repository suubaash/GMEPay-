'use client';
import * as React from 'react';
import { Provider as ReduxProvider } from 'react-redux';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { useServerInsertedHTML } from 'next/navigation';
import { store } from '@/store';
import { partnerTheme } from '@/theme/theme';

/**
 * Client-side providers tree.
 *
 * Emotion SSR cache wired manually (rather than via @mui/material-nextjs)
 * so the app boots without an extra optional dependency. This is the
 * pattern documented in Emotion's Next.js App Router guide.
 */
function EmotionRegistry({ children }: { children: React.ReactNode }) {
  const [{ cache, flush }] = React.useState(() => {
    const cache = createCache({ key: 'mui', prepend: true });
    cache.compat = true;
    const prevInsert = cache.insert;
    let inserted: { name: string; isGlobal: boolean }[] = [];
    cache.insert = (...args) => {
      const serialized = args[1];
      if (cache.inserted[serialized.name] === undefined) {
        inserted.push({
          name: serialized.name,
          isGlobal: !args[0]
        });
      }
      return prevInsert(...args);
    };
    const flush = () => {
      const prev = inserted;
      inserted = [];
      return prev;
    };
    return { cache, flush };
  });

  useServerInsertedHTML(() => {
    const names = flush();
    if (names.length === 0) return null;
    let styles = '';
    let dataEmotionAttribute = cache.key;
    const globals: { name: string; style: string }[] = [];
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

export default function Providers({ children }: { children: React.ReactNode }) {
  return (
    <ReduxProvider store={store}>
      <EmotionRegistry>
        <ThemeProvider theme={partnerTheme}>
          <CssBaseline />
          {children}
        </ThemeProvider>
      </EmotionRegistry>
    </ReduxProvider>
  );
}
