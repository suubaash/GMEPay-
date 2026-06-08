package com.gme.pay.registry.web;

import com.gme.pay.domain.Rule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validate and (in F1+) persist a Rule. Margin invariants are enforced before save. */
@RestController
@RequestMapping("/v1/rules")
public class RuleController {

    @PostMapping("/validate")
    public ResponseEntity<String> validate(@RequestBody Rule rule) {
        rule.validate();
        return ResponseEntity.ok("VALID");
    }
}
