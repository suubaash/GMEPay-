package com.gme.pay.settlement.model;

/**
 * Partner type drives the net/gross settlement classification.
 * LOCAL  — domestic settlement (KRW, no prefunding), uses NET settlement.
 * OVERSEAS — international settlement (USD prefunding), uses GROSS settlement.
 */
public enum PartnerType {
    LOCAL,
    OVERSEAS
}
