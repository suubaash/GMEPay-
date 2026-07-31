package com.gme.pay.scheme.sendmn.dto;

/**
 * Request for POST /internal/scheme/sendmn/verify-qr — carries the raw scanned EMVCo
 * MPM static QR payload. The adapter generates and returns the {@code TX_TOKEN_NO}.
 *
 * @param qrPayload the raw scanned QR payload (required)
 * @param reference the hub's stable partner reference (optional but strongly
 *                  recommended): persisted with the payment BEFORE any Confirm is sent
 *                  so the ADR-016 probe ({@code /status/by-reference/{reference}})
 *                  survives a hub restart
 */
public record VerifyQrRequest(String qrPayload, String reference) {}
