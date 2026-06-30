package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.emvco.EMVCoCrcVerifier;
import com.gme.pay.qr.domain.emvco.EMVCoTlvParser;
import com.gme.pay.qr.exception.QRMalformedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Parses a CPM QR payload produced by {@link CpmPayloadEncoder} back into a {@link CpmTokenPayload}
 * (WBS 5.4-T11).
 *
 * <p>Steps: verify CRC, parse top-level TLV, locate the CPM template (tag 85), parse its sub-TLV,
 * and extract sub-tag 01 (token) and sub-tag 02 (scheme). Any structural problem or blank token
 * raises {@link QRMalformedException}. CPM parsing performs no DB access.
 */
@Component
public class CpmPayloadParser {

    private final int tokenTtlSeconds;

    public CpmPayloadParser(@Value("${qr.zeropay.cpm-token-ttl-seconds:60}") int tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * Decode a CPM payload string.
     *
     * @param rawPayload the full CPM QR content (TLV + CRC)
     * @return the decoded token payload; never null
     * @throws QRMalformedException                                  if the structure is invalid or
     *                                                               the token sub-tag is missing/blank
     * @throws com.gme.pay.qr.exception.QRInvalidChecksumException   if the CRC does not match
     */
    public CpmTokenPayload parseCpmToken(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new QRMalformedException("CPM payload must not be null or blank");
        }
        EMVCoCrcVerifier.verify(rawPayload);

        Map<Integer, String> tags = EMVCoTlvParser.parseTopLevel(rawPayload);
        String template = tags.get(CpmPayloadEncoder.CPM_TEMPLATE_TAG);
        if (template == null) {
            throw new QRMalformedException(
                    "CPM template tag " + CpmPayloadEncoder.CPM_TEMPLATE_TAG + " absent from payload");
        }

        Map<Integer, String> sub = EMVCoTlvParser.parseTemplate(template);
        String token = sub.get(CpmPayloadEncoder.SUB_TAG_TOKEN);
        if (token == null || token.isBlank()) {
            throw new QRMalformedException("CPM token sub-tag (01) missing or blank");
        }
        String schemeId = sub.get(CpmPayloadEncoder.SUB_TAG_SCHEME);

        Instant now = Instant.now();
        return new CpmTokenPayload(token.trim(),
                schemeId == null ? null : schemeId.trim(),
                now, now.plusSeconds(tokenTtlSeconds));
    }
}
