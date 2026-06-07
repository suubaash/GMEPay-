package com.gme.pay.ratefx.web;

import com.gme.pay.ratefx.RateEngine;
import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.RateResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /v1/rates — compute an offer for a payout (API-05 §4). */
@RestController
@RequestMapping("/v1/rates")
public class RateController {

    private final RateEngine engine = new RateEngine();

    @PostMapping
    public RateResult quote(@RequestBody RateInput request) {
        return engine.quote(request);
    }
}
