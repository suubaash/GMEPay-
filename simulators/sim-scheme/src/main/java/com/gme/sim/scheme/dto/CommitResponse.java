package com.gme.sim.scheme.dto;

public record CommitResponse(
        String authId,
        String status,
        String schemeTxnRef,
        String committedAt    // KST ISO-8601
) {}
