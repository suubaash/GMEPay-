package com.gme.pay.reporting.domain;

/**
 * BOK (Bank of Korea) foreign-exchange report type.
 *
 * <ul>
 *   <li>FX1015 – Inbound foreign exchange (money arriving in Korea).</li>
 *   <li>FX1014 – Outbound foreign exchange (money leaving Korea).</li>
 * </ul>
 *
 * Domestic (same-currency) transactions are exempt from BOK reporting.
 * Mapping rule: INBOUND -> FX1015; OUTBOUND -> FX1014; HUB -> FX1015 (provisional, pending OI-03).
 */
public enum BokReportType {
    FX1014,
    FX1015
}
