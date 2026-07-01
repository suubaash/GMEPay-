package com.gme.pay.scheme.nepal.adapter;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.nepal.client.NepalSchemeApiClient;
import com.gme.pay.scheme.nepal.dto.DecodeResponse;
import com.gme.pay.scheme.nepal.dto.StatusResponse;
import com.gme.pay.scheme.nepal.dto.SubmitRequest;
import com.gme.pay.scheme.nepal.dto.SubmitResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Translates payment-executor's canonical {@code /internal/scheme/nepal/...} calls into the
 * Khalti/Fonepay partner API and back. Holds the field-shape mapping so the controller stays thin.
 */
@Service
public class NepalSchemeAdapter {

    private final NepalSchemeApiClient client;

    public NepalSchemeAdapter(NepalSchemeApiClient client) {
        this.client = client;
    }

    /**
     * Decodes a scanned QR into canonical merchant fields via the unsigned {@code /parse/} surface.
     * The partner returns {@code trxAmount} as a rupee string (null if static); we convert to paisa.
     */
    public DecodeResponse decode(String qs) {
        if (qs == null || qs.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "decode: qs is required");
        }
        NepalSchemeApiClient.ParseResponse p = client.parse(qs);
        String hint = p.merchantInfoExtra() != null ? p.merchantInfoExtra() : p.format();
        String network = deriveNetwork(hint);
        Long amountPaisa = rupeesToPaisa(p.trxAmount());
        String currency = p.trxCurrency() == null ? "NPR" : p.trxCurrency();
        return new DecodeResponse(
                network,
                null,               // /parse/ carries no distinct merchant_id; merchantData holds raw TLV
                p.merchantName(),
                p.merchantCity(),
                amountPaisa,
                currency
        );
    }

    /**
     * Submits a payment — authorize+commit combined (Nepal {@code pay} is synchronous single-shot).
     * Maps the partner {@code idx} to {@code schemeTxnRef} and the sim {@code detail} to a canonical state.
     */
    public SubmitResponse submit(SubmitRequest req) {
        if (req.qs() == null || req.qs().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "submit: qs is required");
        }
        if (req.reference() == null || req.reference().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "submit: reference is required");
        }
        NepalSchemeApiClient.PayResponse pay = client.pay(
                req.qs(), req.amountPaisa(), req.reference(),
                req.mobile(), req.purpose(), req.remarks());

        long amount = parsePaisa(pay.amount(), req.amountPaisa());
        String state = deriveState(pay.detail());
        return new SubmitResponse(pay.idx(), state, amount);
    }

    /** Looks up the state of a submitted payment by its unique reference. */
    public StatusResponse status(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status: reference is required");
        }
        NepalSchemeApiClient.StatusResponse s = client.status(reference, null);
        return new StatusResponse(s.state());
    }

    // --------------------------------------------------------------- helpers

    /** {@code pay} is synchronous: a stored txn means APPROVED unless the detail says pending. */
    private String deriveState(String detail) {
        String d = detail == null ? "" : detail.toLowerCase();
        if (d.contains("pending")) return "PENDING";
        if (d.contains("reject")) return "REJECTED";
        return "APPROVED";
    }

    private String deriveNetwork(String hint) {
        if (hint == null) return "fonepay";
        String h = hint.toLowerCase();
        if (h.contains("nepalpay")) return "nepalpay";
        if (h.contains("unionpay")) return "unionpay";
        if (h.contains("khalti")) return "khalti";
        return "fonepay";
    }

    private Long rupeesToPaisa(String rupees) {
        if (rupees == null || rupees.isBlank()) return null;
        try {
            return new BigDecimal(rupees.trim())
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private long parsePaisa(String paisa, long fallback) {
        if (paisa == null || paisa.isBlank()) return fallback;
        try {
            return Long.parseLong(paisa.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
