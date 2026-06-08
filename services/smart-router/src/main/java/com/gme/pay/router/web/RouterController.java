package com.gme.pay.router.web;

import com.gme.pay.router.SchemeRouter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /v1/route?country=KR -> scheme(s) for that country. */
@RestController
@RequestMapping("/v1/route")
public class RouterController {

    private final SchemeRouter router = new SchemeRouter();

    @GetMapping
    public List<String> route(@RequestParam("country") String country) {
        return router.list(country);
    }
}
