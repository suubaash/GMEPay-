package com.gme.sim.scheme.dto;

import java.math.BigDecimal;

public record AuthorizeResponse(
        String authId,
        String status,
        String schemeRef,
        String merchantId,
        BigDecimal amount,
        String currency,
        String asOf          // KST ISO-8601
) {}
