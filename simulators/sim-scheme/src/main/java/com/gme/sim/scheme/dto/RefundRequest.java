package com.gme.sim.scheme.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RefundRequest(@NotNull BigDecimal amount) {}
