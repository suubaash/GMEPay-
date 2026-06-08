package com.gme.pay.notify.dto;

import java.util.List;

/**
 * Pageable list of webhook configurations for a given partner.
 */
public record WebhookConfigListResponse(
        List<WebhookConfigResponse> configs,
        int totalCount
) {}
