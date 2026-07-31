package com.gme.pay.prefunding.aml;

/**
 * The measurable quantities an {@link AmlMonitoringRules.Rule} may be written against — gap T5-3.
 *
 * <p>This enum is the complete vocabulary, and it is deliberately small. Every member is a plain,
 * checkable arithmetic fact about the {@code cumulative_usage_ledger} rows in a window: a sum, a
 * count, or the largest single day of either. There is no "risk score", no "structuring indicator",
 * no "unusual pattern" member, because prefunding has no basis on which to define one and a metric
 * whose meaning is a judgement call would let a threshold in a properties file masquerade as a
 * compliance model.
 *
 * <p><b>What this is NOT.</b> A metric here is EVIDENCE. Naming a metric does not make prefunding an
 * AML control: it makes prefunding able to say, reproducibly and from an append-only ledger, what a
 * partner's volume and velocity actually were. Whether any particular value of one of these metrics
 * is suspicious is a compliance judgement that lives in the threshold, and the thresholds ship empty
 * (see {@link AmlMonitoringRules}).
 *
 * <p>The two {@code WINDOW_*} members aggregate the whole window; the two {@code MAX_DAILY_*} members
 * take the single largest KST day inside it. Both shapes are needed because they catch opposite
 * things: a slow bleed across 30 days moves {@code WINDOW_NET_USD} and barely moves
 * {@code MAX_DAILY_NET_USD}, and one enormous day does the reverse.
 */
public enum AmlMetric {

    /** SUM of the signed {@code amount_usd} across the whole window (charges minus reverses), in USD. */
    WINDOW_NET_USD,

    /** Charges minus reverses across the whole window, as a count of transactions. */
    WINDOW_NET_TXN_COUNT,

    /** The largest single-KST-day net USD inside the window. Zero when the window has no activity. */
    MAX_DAILY_NET_USD,

    /** The largest single-KST-day net transaction count inside the window. */
    MAX_DAILY_NET_TXN_COUNT
}
