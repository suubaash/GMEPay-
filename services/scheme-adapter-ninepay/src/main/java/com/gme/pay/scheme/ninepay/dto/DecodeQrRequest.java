package com.gme.pay.scheme.ninepay.dto;

/** Request for POST /scheme/decode-qr — carries the raw scanned QR string (≤1000 chars). */
public record DecodeQrRequest(String qr) {}
