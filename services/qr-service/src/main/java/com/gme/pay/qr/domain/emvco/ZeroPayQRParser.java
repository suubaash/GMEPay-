package com.gme.pay.qr.domain.emvco;

import com.gme.pay.qr.exception.QRCurrencyMismatchException;
import com.gme.pay.qr.exception.QRMalformedException;
import com.gme.pay.qr.exception.QRUnknownSchemeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * EMVCo QR parser for the ZeroPay scheme (SCH-06 §3.4, WBS 5.4-T06 / 5.4-T07).
 *
 * <p>Parse steps:
 * <ol>
 *   <li>CRC-16/CCITT verification via {@link EMVCoCrcVerifier}</li>
 *   <li>Tag 00 must equal "01" (format indicator)</li>
 *   <li>Mandatory tags 52, 53, 58, 59, 60, 63 must all be present</li>
 *   <li>Tag 53 must equal "410" (KRW) for ZeroPay</li>
 *   <li>MAI slot ({@code zeropay.mai-tag}, default 26) must be present with sub-tags 01 and 02</li>
 * </ol>
 */
@Component
public class ZeroPayQRParser {

    /** Scheme identifier constant used by the REST layer. */
    public static final String SCHEME_ID = "ZEROPAY";

    /** Expected currency code for ZeroPay (KRW numeric ISO 4217). */
    private static final String ZEROPAY_CURRENCY = "410";

    private final int maiTag;

    public ZeroPayQRParser(@Value("${qr.zeropay.mai-tag:26}") int maiTag) {
        this.maiTag = maiTag;
    }

    /**
     * Parse a raw ZeroPay MPM QR payload and return a fully populated {@link ParsedQRPayload}.
     *
     * @param rawPayload the complete QR string (ASCII TLV + CRC)
     * @return parsed payload; never null
     * @throws com.gme.pay.qr.exception.QRInvalidChecksumException if CRC verification fails
     * @throws QRMalformedException                                  if a mandatory tag is absent or
     *                                                               the TLV structure is invalid
     * @throws QRCurrencyMismatchException                           if tag 53 != "410"
     * @throws QRUnknownSchemeException                              if the MAI slot is absent
     */
    public ParsedQRPayload parse(String rawPayload) {
        // Step 1: CRC verification (must happen first per spec)
        EMVCoCrcVerifier.verify(rawPayload);

        // Step 2: top-level TLV parse
        Map<Integer, String> tags = EMVCoTlvParser.parseTopLevel(rawPayload);

        // Step 3: format indicator
        String fmt = requireTag(tags, 0, "format indicator (tag 00)");
        if (!"01".equals(fmt)) {
            throw new QRMalformedException("Tag 00 (format indicator) must be '01', got: " + fmt);
        }

        // Step 4: mandatory tags
        String mcc          = requireTag(tags, 52, "MCC (tag 52)");
        String currency     = requireTag(tags, 53, "currency (tag 53)");
        String countryCode  = requireTag(tags, 58, "country code (tag 58)");
        String merchantName = requireTag(tags, 59, "merchant name (tag 59)");
        String merchantCity = requireTag(tags, 60, "merchant city (tag 60)");
        requireTag(tags, 63, "CRC (tag 63)"); // already verified, but must be present

        // Step 5: currency check
        if (!ZEROPAY_CURRENCY.equals(currency)) {
            throw new QRCurrencyMismatchException(
                    "ZeroPay requires currency 410 (KRW) in tag 53, got: " + currency);
        }

        // Step 6: optional encoded amount (tag 54)
        BigDecimal encodedAmount = null;
        if (tags.containsKey(54)) {
            try {
                encodedAmount = new BigDecimal(tags.get(54));
            } catch (NumberFormatException e) {
                throw new QRMalformedException("Tag 54 (amount) is not a valid decimal: " + tags.get(54));
            }
        }

        // Step 7: MAI slot extraction
        String maiValue = tags.get(maiTag);
        if (maiValue == null) {
            // fallback: scan all MAI range 26-51
            for (int t = 26; t <= 51; t++) {
                if (tags.containsKey(t)) {
                    maiValue = tags.get(t);
                    // use the first found MAI — scheme config should set the right tag
                    break;
                }
            }
        }
        if (maiValue == null) {
            throw new QRUnknownSchemeException(
                    "ZeroPay MAI tag not found in tags 26-51 (configured tag: " + maiTag + ")");
        }

        Map<Integer, String> maiTags = EMVCoTlvParser.parseTemplate(maiValue);

        String merchantId = requireSubTag(maiTags, 1, "MAI sub-tag 01 (merchant_id)");
        String qrCodeId   = requireSubTag(maiTags, 2, "MAI sub-tag 02 (qr_code_id)");

        return new ParsedQRPayload(
                rawPayload,
                Integer.parseInt(fmt),
                currency,
                merchantName.trim(),
                merchantCity.trim(),
                mcc,
                countryCode,
                maiTag,
                merchantId.trim(),
                qrCodeId.trim(),
                encodedAmount,
                true   // CRC already verified above
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String requireTag(Map<Integer, String> tags, int tag, String description) {
        String value = tags.get(tag);
        if (value == null) {
            throw new QRMalformedException("Mandatory tag missing: " + description);
        }
        return value;
    }

    private static String requireSubTag(Map<Integer, String> tags, int subTag, String description) {
        String value = tags.get(subTag);
        if (value == null) {
            throw new QRMalformedException("Mandatory MAI sub-tag missing: " + description);
        }
        return value;
    }
}
