import { SERVICES, baseUrl } from '../config';
import type { ServiceHealth } from '../shared/types';

/**
 * Pings every service. Any HTTP response (even 404/401) means the process is up
 * and serving; only a connection error counts as down. Tries the Spring actuator
 * health endpoint first, then the fleet's /v1/_probe.
 */
export async function checkHealth(): Promise<ServiceHealth[]> {
  const entries = Object.entries(SERVICES);
  return Promise.all(
    entries.map(async ([name, port]): Promise<ServiceHealth> => {
      const url = baseUrl(name);
      for (const path of ['/actuator/health', '/v1/_probe']) {
        try {
          const res = await fetch(url + path, { signal: AbortSignal.timeout(2500) });
          return { name, port, url, up: true, status: res.status };
        } catch {
          /* try next path */
        }
      }
      return { name, port, url, up: false, note: 'connection refused / timeout' };
    }),
  );
}
