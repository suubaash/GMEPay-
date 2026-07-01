package com.gme.pay.scheme.nepal.dto;

/**
 * Response for GET /internal/scheme/nepal/status.
 *
 * @param state partner state: APPROVED / PENDING / REJECTED / REVERSED / Error
 */
public record StatusResponse(String state) {}
