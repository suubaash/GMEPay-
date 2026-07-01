package com.gme.pay.scheme.nepal.dto;

/** Request for POST /internal/scheme/nepal/decode — carries the raw scanned QR string. */
public record DecodeRequest(String qs) {}
