package com.gme.pay.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Paged envelope for {@code GET /v1/admin/journals}, mirroring revenue-ledger's shape so the
 * response passes through unchanged.
 *
 * <pre>
 *   { "items": [ JournalView, … ], "page": 0, "size": 50, "total": 137 }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JournalPage(List<JournalView> items, int page, int size, long total) {}
