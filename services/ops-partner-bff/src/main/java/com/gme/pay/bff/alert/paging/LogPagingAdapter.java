package com.gme.pay.bff.alert.paging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback {@link PagingPort} used when no on-call webhook URL is configured. Wired as a
 * {@code @ConditionalOnMissingBean(PagingPort.class)} bean in {@link PagingConfig}: the
 * {@link WebhookPagingAdapter} registers a {@link PagingPort} only when
 * {@code gmepay.ops.paging.webhook-url} is set, so with no URL this fallback wins. Logs the
 * page at {@code WARN} so the paging path is functional (and visible) out of the box with
 * zero configuration. Never throws.
 */
public class LogPagingAdapter implements PagingPort {

    static final String CHANNEL = "log";

    private static final Logger log = LoggerFactory.getLogger(LogPagingAdapter.class);

    @Override
    public PageOutcome page(PageRequest request) {
        log.warn("ON-CALL PAGE (log-only; no gmepay.ops.paging.webhook-url configured): "
                        + "severity={} type={} subjectRef={} occurredAt={} detail={}",
                request.severity(), request.alertType(), request.subjectRef(),
                request.occurredAt(), request.detail());
        return PageOutcome.delivered(CHANNEL);
    }
}
