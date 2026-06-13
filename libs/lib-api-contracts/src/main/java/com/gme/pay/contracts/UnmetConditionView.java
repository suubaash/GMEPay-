package com.gme.pay.contracts;

/**
 * One unmet activation pre-condition (Slice 8 activation gate). Rides inside
 * {@link ActivationGateView#unmet()}.
 *
 * @param code        stable machine-readable code (e.g. {@code LEGAL_NAME_MISSING},
 *                    {@code SANCTIONS_NOT_CLEAR}) the Admin UI keys its checklist
 *                    rendering off. The roster lives in config-registry's
 *                    {@code ActivationGateService}.
 * @param description human-readable explanation of what is missing and how to
 *                    satisfy it.
 */
public record UnmetConditionView(String code, String description) {
}
