package com.gme.pay.scheme.ninepay.dto;

/**
 * Acknowledgement body for POST /scheme/ipn. 9Pay's expected ACK contract is undocumented
 * (open issue O5) — we return HTTP 200 with this body on acceptance and a 4xx ApiError on
 * signature failure so 9Pay's retry machinery (if any) re-delivers.
 *
 * @param status  "RECEIVED"
 * @param payoutStatus the canonical payout status after applying the IPN (null when the
 *                     referenced request_id is unknown to this adapter)
 */
public record IpnAck(String status, String payoutStatus) {}
