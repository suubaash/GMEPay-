package com.gme.pay.bff.web;

import com.gme.pay.bff.client.RatesClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * Admin UI Rates Preview endpoint. Lets operators preview the 5-step USD-pivot
 * quote math for a hypothetical partner before locking a real rate via
 * {@code rate-fx}.
 *
 * <p>Phase-C4 endpoints:
 * <ul>
 *   <li>{@code POST /v1/admin/rates/preview} — preview a quote (no commitment, no quote id)
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/rates")
public class RatesController {

    private final RatesClient rates;

    public RatesController(RatesClient rates) {
        this.rates = rates;
    }

    @PostMapping("/preview")
    public RatesClient.RateQuotePreview preview(@RequestBody RatesClient.RateQuoteRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        BigDecimal amount = body.amount();
        if (amount == null || amount.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amount must be a non-negative decimal");
        }
        String fromCcy = body.fromCcy();
        String toCcy = body.toCcy();
        if (fromCcy == null || fromCcy.isBlank() || toCcy == null || toCcy.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fromCcy and toCcy are required");
        }
        return rates.previewQuote(body);
    }
}
