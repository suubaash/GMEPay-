package com.gme.pay.scheme.zeropay.adapter.model;

/** ZeroPay batch file type codes per SCH-06 §7.3 schedule. */
public enum BatchType {
    ZP0011, // Payment result registration — OUTBOUND 02:00 KST
    ZP0012, // Payment result response     — INBOUND  05:00 KST
    ZP0021, // Refund result registration  — OUTBOUND 02:00 KST
    ZP0022, // Refund result response      — INBOUND  05:00 KST
    ZP0041, // Merchant delta sync         — INBOUND  daily
    ZP0043, // QR code delta sync          — INBOUND  daily
    ZP0045, // Franchise merchant delta    — INBOUND  daily
    ZP0047, // Franchise group delta       — INBOUND  daily
    ZP0051, // Merchant full sync          — INBOUND  weekly
    ZP0053, // QR code full sync           — INBOUND  weekly
    ZP0055, // Franchise full sync         — INBOUND  weekly
    ZP0061, // Settlement request morning  — OUTBOUND 05:00 KST
    ZP0062, // Settlement response morning — INBOUND  10:00 KST
    ZP0063, // Settlement request afternoon— OUTBOUND 14:00 KST
    ZP0064, // Settlement response afternoon— INBOUND  19:00 KST
    ZP0065, // Settlement request evening  — OUTBOUND 22:00 KST
    ZP0066  // Settlement request final    — OUTBOUND 22:00 KST
}
