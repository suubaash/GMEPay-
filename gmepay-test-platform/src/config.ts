/**
 * GMEPay+ service topology — ports come straight from the platform's
 * `code/run-fleet.ps1` fleet definition. Override the host with GMEPAY_HOST
 * if you run the services on another machine.
 */
export const SERVICES: Record<string, number> = {
  'api-gateway': 18080,
  'config-registry': 18081,
  'transaction-mgmt': 18082,
  'merchant-qr-data': 18083,
  'payment-executor': 18084,
  'auth-identity': 18085,
  'notification-webhook': 18086,
  'reporting-compliance': 18087,
  prefunding: 18088,
  'qr-service': 18089,
  'scheme-adapter-zeropay': 18090,
  'smart-router': 18091,
  'revenue-ledger': 18092,
  'settlement-reconciliation': 18093,
  'ops-partner-bff': 18095,
  'kyb-adapter': 18098,
  'rate-fx': 18101,
  'sim-rate-provider': 9101,
  'sim-scheme': 9102,
  'sim-wallet': 9103,
  'sim-merchant': 9104,
  'sim-gmeremit': 9105,
};

export const HOST = process.env.GMEPAY_HOST ?? 'localhost';

export function baseUrl(service: string): string {
  const port = SERVICES[service];
  if (!port) throw new Error(`Unknown service '${service}' — not in config.ts`);
  return `http://${HOST}:${port}`;
}

// --- ZeroPay EMVCo QR builder ----------------------------------------------
// The ZeroPay parser requires the Merchant Account Info template (tag 29) to
// carry sub-tag 00=com.zeropay, 01=merchantId, 02=qr_code_id, plus a valid
// CRC-16/CCITT-FALSE checksum in tag 63. We build it here so tests use a
// well-formed payload (the .smoke fixture was missing sub-tag 02).
function tlv(tag: string, value: string): string {
  return tag + value.length.toString().padStart(2, '0') + value;
}

function crc16ccitt(input: string): string {
  let crc = 0xffff;
  for (let i = 0; i < input.length; i++) {
    crc ^= input.charCodeAt(i) << 8;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x8000) !== 0 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc.toString(16).toUpperCase().padStart(4, '0');
}

export function buildZeroPayQr(merchantId: string, qrCodeId: string): string {
  const mai = tlv('00', 'com.zeropay') + tlv('01', merchantId) + tlv('02', qrCodeId);
  const body =
    tlv('00', '01') + // payload format indicator
    tlv('52', '5999') + // MCC
    tlv('53', '410') + // currency = KRW (ISO-4217 numeric)
    tlv('58', 'KR') + // country
    tlv('59', 'Smoke Merchant') + // merchant name
    tlv('60', 'Seoul') + // city
    tlv('29', mai); // merchant account info (ZeroPay MAI)
  const withCrcTag = body + '6304';
  return withCrcTag + crc16ccitt(withCrcTag);
}

// A merchant pre-seeded in merchant-qr-data's InMemoryMerchantRepository (and
// matching the sim-scheme ZeroPay sandbox), so the full wallet path resolves.
const MERCHANT_ID = 'M0000000001';
const QR_CODE_ID = 'QR00000000000000001A';

/** Known-good test data; the QR is generated with a valid structure + CRC. */
export const FIXTURES = {
  qrPayload: buildZeroPayQr(MERCHANT_ID, QR_CODE_ID),
  merchantId: MERCHANT_ID,
  qrCodeId: QR_CODE_ID,
  gmeremitUser: 'gmeremit-user-001',
  sendmnUser: 'sendmn-user-001',
  sendmnPartnerCode: 'SENDMN',
};

export const API_PORT = Number(process.env.PORT ?? 4000);
export const HTTP_TIMEOUT_MS = Number(process.env.GMEPAY_TIMEOUT_MS ?? 8000);
