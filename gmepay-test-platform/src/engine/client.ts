import { baseUrl } from '../config';
import { http, HttpResult, NetworkError } from './http';
import { Recorder } from './assert';

/** Raised when a service the test depends on is down → runner maps to BLOCKED. */
export class ServiceDownError extends Error {
  constructor(public service: string, public url: string) {
    super(`Service '${service}' is not reachable at ${url} - is the fleet running?`);
    this.name = 'ServiceDownError';
  }
}

/**
 * Thin HTTP client over the GMEPay+ REST surface. Every call is logged into the
 * recorder so the dashboard can show exactly what the test did, and a dead
 * service surfaces as ServiceDownError (→ BLOCKED) rather than a raw failure.
 */
export class GmePayClient {
  constructor(private rec: Recorder) {}

  async call(
    service: string,
    method: string,
    path: string,
    body?: unknown,
    headers?: Record<string, string>,
  ): Promise<HttpResult> {
    const url = baseUrl(service) + path;
    this.rec.request(`${method} ${url}`, body ?? undefined);
    let res: HttpResult;
    try {
      res = await http(method, url, body, headers);
    } catch (e) {
      if (e instanceof NetworkError) throw new ServiceDownError(service, url);
      throw e;
    }
    this.rec.info(`← ${res.status} (${res.ms} ms)`, res.json ?? res.text ?? null);
    return res;
  }

  // --- Convenience wrappers for the endpoints the use cases exercise ---------

  /** POST /v1/pay on payment-executor — the wallet money path (GMEREMIT | SENDMN). */
  pay(body: {
    qrPayload: string;
    amountKrw: string;
    partner: 'GMEREMIT' | 'SENDMN';
    userRef: string;
  }) {
    return this.call('payment-executor', 'POST', '/v1/pay', body);
  }

  refund(schemeTxnRef: string, body: unknown) {
    return this.call(
      'payment-executor',
      'POST',
      `/v1/pay/${encodeURIComponent(schemeTxnRef)}/refund`,
      body,
    );
  }

  /** POST /v1/rates on rate-fx — the 5-step FX/charge engine. */
  rate(body: Record<string, unknown>) {
    return this.call('rate-fx', 'POST', '/v1/rates', body);
  }

  prefundingBalance(partnerCode: string) {
    return this.call('prefunding', 'GET', `/v1/prefunding/${encodeURIComponent(partnerCode)}/balance`);
  }

  merchantByQr(qr: string) {
    return this.call('merchant-qr-data', 'GET', `/v1/merchants/${encodeURIComponent(qr)}`);
  }
}
