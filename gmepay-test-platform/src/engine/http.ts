import { HTTP_TIMEOUT_MS } from '../config';

export interface HttpResult {
  ok: boolean;
  status: number;
  json: any;
  text: string;
  ms: number;
}

/** Raised when the target service cannot be reached at all (connection refused / timeout). */
export class NetworkError extends Error {
  constructor(public url: string, public cause: unknown) {
    super(`Cannot reach ${url}: ${(cause as Error)?.message ?? cause}`);
    this.name = 'NetworkError';
  }
}

export async function http(
  method: string,
  url: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<HttpResult> {
  const started = Date.now();
  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers: { 'content-type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
    });
  } catch (e) {
    throw new NetworkError(url, e);
  }
  const ms = Date.now() - started;
  const text = await res.text();
  let json: any = undefined;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    /* non-JSON body — leave json undefined, keep text */
  }
  return { ok: res.ok, status: res.status, json, text, ms };
}
