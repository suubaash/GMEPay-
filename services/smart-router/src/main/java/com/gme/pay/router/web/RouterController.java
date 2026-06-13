package com.gme.pay.router.web;

import com.gme.pay.router.SchemeRouter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /v1/route?country=KR -> scheme(s) for that country (data-driven, Slice 7). */
@RestController
@RequestMapping("/v1/route")
public class RouterController {

    private final SchemeRouter router;

    public RouterController(SchemeRouter router) {
        this.router = router;
    }

    @GetMapping
    public List<String> route(@RequestParam("country") String country) {
        return router.list(country);
    }

    /** Per-partner override: the schemes wired to one partner's V022 rows. */
    @GetMapping("/partners/{partnerCode}")
    public List<String> routeForPartner(@PathVariable String partnerCode) {
        return router.resolveForPartner(partnerCode);
    }
}
