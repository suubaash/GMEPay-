package com.gme.pay.txn.api.dto;

import java.util.List;

/**
 * Paged response for GET /v1/transactions.
 *
 * <p>Canonical wire shape (consumers align to these field names):
 * <pre>
 * {
 *   "content":       [ ...TransactionResponse... ],
 *   "page":          0,
 *   "size":          20,
 *   "totalElements": 142
 * }
 * </pre>
 *
 * <p>Consumers: reporting-compliance RestTransactionClient, settlement-reconciliation
 * RestTransactionQueryClient.
 */
public record TransactionQueryPageResponse(
        List<TransactionResponse> content,
        int page,
        int size,
        long totalElements
) {
    public static TransactionQueryPageResponse of(List<TransactionResponse> content,
                                                   int page, int size, long totalElements) {
        return new TransactionQueryPageResponse(content, page, size, totalElements);
    }
}
