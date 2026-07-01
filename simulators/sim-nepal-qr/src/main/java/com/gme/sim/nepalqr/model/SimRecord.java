package com.gme.sim.nepalqr.model;

import java.util.Map;

/**
 * One persisted inbound request + its response. EVERY call to EVERY mocked
 * endpoint produces one of these. Serialized directly to JSON by the inspection
 * endpoints, so field names are the public contract.
 */
public class SimRecord {

    public String id;
    public String endpoint;                    // e.g. "/qrscan-thirdparty/pay/"
    public String receivedAt;                  // ISO-8601 KST
    public Map<String, String> relevantRequestHeaders;
    public String rawRequestBody;              // exactly as received
    public Object decodedPayload;              // base64-decoded JSON payload (when applicable), else null
    public int responseStatus;
    public Object responseBody;
    public String reference;                   // pay/status reference, when present
    public String idx;                         // generated txn idx, when a txn was created
    public String qs;                          // decoded-QR string, when present
    public Long amountPaisa;                   // amount in paisa, when present
    public String state;                       // APPROVED | PENDING | REJECTED | REVERSED, when applicable
}
