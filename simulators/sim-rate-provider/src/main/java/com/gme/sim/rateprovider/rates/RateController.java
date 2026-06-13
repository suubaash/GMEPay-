package com.gme.sim.rateprovider.rates;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fake xe.com-style FX rate endpoint.
 *
 * <ul>
 *   <li>GET /v1/rates?base=USD&amp;quote=KRW  — single cross rate</li>
 *   <li>GET /v1/rates?base=USD              — all quotes off USD base</li>
 *   <li>GET /v1/rates/pairs                 — list of supported pairs</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/rates")
public class RateController {

    private static final String SOURCE = "SIM_XE";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_KST =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST);

    private final RateStore store;

    public RateController(RateStore store) {
        this.store = store;
    }

    /**
     * GET /v1/rates?base=X&quote=Y  — single mid-rate.
     * GET /v1/rates?base=X          — full quote set off base.
     */
    @GetMapping
    public ResponseEntity<?> rates(
            @RequestParam String base,
            @RequestParam(required = false) String quote) {

        if (!store.supports(base)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unsupported base: " + base));
        }

        String asOf = ISO_KST.format(Instant.now());

        if (quote != null) {
            // Single pair
            if (!store.supports(quote)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "unsupported quote: " + quote));
            }
            String rate = store.crossRate(base, quote).toPlainString();
            return ResponseEntity.ok(new SingleRateResponse(base, quote, rate, asOf, SOURCE));
        }

        // All quotes off base
        Map<String, String> quotes = new LinkedHashMap<>();
        store.allRates().forEach((ccy, r) -> {
            if (!ccy.equals(base)) {
                quotes.put(ccy, store.crossRate(base, ccy).toPlainString());
            }
        });
        return ResponseEntity.ok(new MultiRateResponse(base, asOf, SOURCE, quotes));
    }

    /** GET /v1/rates/pairs — list all supported currency pairs (USD-base + cross). */
    @GetMapping("/pairs")
    public List<String> pairs() {
        List<String> ccys = List.copyOf(store.allRates().keySet());
        // USD → each quote
        List<String> usdPairs = ccys.stream()
                .map(q -> "USD/" + q)
                .collect(Collectors.toList());
        // cross pairs (non-USD base → non-USD quote)
        List<String> crossPairs = ccys.stream()
                .flatMap(b -> ccys.stream()
                        .filter(q -> !q.equals(b))
                        .map(q -> b + "/" + q))
                .collect(Collectors.toList());
        usdPairs.addAll(crossPairs);
        // also quote/USD
        ccys.stream().map(b -> b + "/USD").forEach(usdPairs::add);
        return usdPairs;
    }
}
