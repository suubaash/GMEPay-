import { baseUrl } from '../config';
import { http, HttpResult, NetworkError } from './http';
import { Recorder } from './assert';
import {
  AuthRejectedError,
  CREDENTIAL_LABEL,
  CredentialKind,
  MissingCredentialError,
  credentialHeaders,
  credentialStatus,
  decodeClaims,
  partnerIdOf,
  permissionsOf,
} from './credentials';

/** Raised when a service the test depends on is down → runner maps to BLOCKED. */
export class ServiceDownError extends Error {
  constructor(public service: string, public url: string) {
    super(`Service '${service}' is not reachable at ${url} - is the fleet running?`);
    this.name = 'ServiceDownError';
  }
}

export interface CallOptions {
  /**
   * Which credential to present. Omitted = 'none'. When the credential is not
   * configured the call throws MissingCredentialError before any HTTP happens, so
   * the report names the missing env var instead of showing a bare 401.
   */
  as?: CredentialKind;
  /** Extra headers merged after the credential headers. */
  headers?: Record<string, string>;
  /**
   * Set when a 401/403 is the EXPECTED outcome (negative tests). Suppresses the
   * automatic AuthRejectedError so the test can assert the status itself.
   */
  expectAuthFailure?: boolean;
}

/**
 * Thin HTTP client over the GMEPay+ REST surface. Every call is logged into the
 * recorder so the dashboard can show exactly what the test did, and a dead
 * service surfaces as ServiceDownError (→ BLOCKED) rather than a raw failure.
 *
 * Since the 2026-07-28 hardening the platform is default-deny, so the client also
 * owns credential presentation: `as: 'internal'` stamps `X-Gme-Internal`,
 * `as: 'oidc-admin'` fetches and stamps a bearer token, and an unexpected 401/403
 * is converted into an AuthRejectedError that says WHICH credential was presented
 * and what the platform most likely objected to.
 */
export class GmePayClient {
  constructor(private rec: Recorder) {}

  async call(
    service: string,
    method: string,
    path: string,
    body?: unknown,
    headersOrOptions?: Record<string, string> | CallOptions,
  ): Promise<HttpResult> {
    const opts: CallOptions = isCallOptions(headersOrOptions)
      ? headersOrOptions
      : { headers: headersOrOptions };
    const kind: CredentialKind = opts.as ?? 'none';

    // Resolve the credential BEFORE the request: a missing one is a configuration
    // problem, reported as BLOCKED with the variable name, not as a 401.
    let credHeaders: Record<string, string> = {};
    if (kind !== 'none') {
      credHeaders = await credentialHeaders(kind);
      this.rec.info(`presenting ${CREDENTIAL_LABEL[kind]}`, describeCredential(kind, credHeaders));
    }

    const url = baseUrl(service) + path;
    this.rec.request(`${method} ${url}`, body ?? undefined);
    let res: HttpResult;
    try {
      res = await http(method, url, body, { ...credHeaders, ...(opts.headers ?? {}) });
    } catch (e) {
      if (e instanceof NetworkError) throw new ServiceDownError(service, url);
      throw e;
    }
    this.rec.info(`← ${res.status} (${res.ms} ms)`, res.json ?? res.text ?? null);

    if ((res.status === 401 || res.status === 403) && !opts.expectAuthFailure) {
      throw new AuthRejectedError(
        res.status,
        service,
        `${method} ${path}`,
        kind,
        diagnose(kind, res),
      );
    }
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

  /**
   * POST /v1/pay/{ref}/refund. `schemeId` matters: without it the refund routes to
   * the ZeroPay adapter (legacy default), so a cross-border refund would come back
   * as a foreign ZeroPay decline instead of 422 SCHEME_OPERATION_UNSUPPORTED.
   */
  refund(schemeTxnRef: string, body: unknown) {
    return this.call(
      'payment-executor',
      'POST',
      `/v1/pay/${encodeURIComponent(schemeTxnRef)}/refund`,
      body,
    );
  }

  /** POST /v1/rates on rate-fx — the 5-step FX/charge engine. Not internal-gated. */
  rate(body: Record<string, unknown>) {
    return this.call('rate-fx', 'POST', '/v1/rates', body);
  }

  /** prefunding's entire /v1/prefunding/** API is internal-gated (T0-5). */
  prefundingBalance(partnerCode: string) {
    return this.call(
      'prefunding',
      'GET',
      `/v1/prefunding/${encodeURIComponent(partnerCode)}/balance`,
      undefined,
      { as: 'internal' },
    );
  }

  merchantByQr(qr: string) {
    return this.call('merchant-qr-data', 'GET', `/v1/merchants/${encodeURIComponent(qr)}`);
  }
}

function isCallOptions(v: unknown): v is CallOptions {
  if (!v || typeof v !== 'object') return false;
  return 'as' in v || 'expectAuthFailure' in v || 'headers' in v;
}

/**
 * Describe what is being presented WITHOUT leaking the secret. For bearer tokens
 * the claims are the useful part, because authorization is claim-driven now.
 */
function describeCredential(
  kind: CredentialKind,
  headers: Record<string, string>,
): Record<string, unknown> {
  if (kind === 'internal') return { header: 'X-Gme-Internal', value: '<redacted>' };
  const auth = headers.Authorization;
  if (auth?.startsWith('Bearer ')) {
    const claims = decodeClaims(auth.slice(7));
    return {
      header: 'Authorization: Bearer <redacted>',
      iss: claims?.iss,
      sub: claims?.sub,
      partner_id: partnerIdOf(claims),
      permissions: permissionsOf(claims),
    };
  }
  return { credential: CREDENTIAL_LABEL[kind] };
}

/**
 * Turn a 401/403 into an actionable sentence. This is the whole value of the tool:
 * whoever reads the row must learn which credential was missing or insufficient.
 */
function diagnose(kind: CredentialKind, res: HttpResult): string {
  const status = credentialStatus(kind);
  const code = res.json?.code;
  const parts: string[] = [];

  if (kind === 'none') {
    parts.push(
      'This call presented NO credential. The surface was anonymous when the test was written; ' +
        'it is gated now — the test needs `as:` set to the right credential.',
    );
  } else if (res.status === 401) {
    parts.push('The credential was presented but not accepted (401).');
  } else {
    parts.push('The credential authenticated but was not authorized for this surface (403).');
  }

  if (code) parts.push(`Platform error code: ${code}.`);
  else if (res.status === 401 && !res.text)
    parts.push(
      'Empty 401 body — that is the OAuth2 resource-server entry point (HttpStatusEntryPoint), ' +
        'i.e. the bearer token was missing, expired or minted by the wrong issuer.',
    );

  parts.push(status.hint);
  if (status.suppliedBy.length) parts.push(`Supplied from: ${status.suppliedBy.join(', ')}.`);
  return parts.join(' ');
}

export { AuthRejectedError, MissingCredentialError };
