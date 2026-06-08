package com.gme.pay.domain;

/** How a rule's leg cost rate is resolved (RATE-04 §3). */
public enum RateSource {
    IDENTITY,
    LIVE,
    MANUAL,
    PARTNER
}
