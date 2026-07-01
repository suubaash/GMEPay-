package com.gme.sim.nepalqr.controller;

import com.gme.sim.nepalqr.model.NepalQrStore;
import com.gme.sim.nepalqr.model.SimRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Inspection endpoints — the whole point of the mock: look at what GMEPay+ sent
 * and what the sim replied when creating a transaction.
 *
 *   GET /sim/nepal-qr/records                 newest-first, optional ?reference=
 *   GET /sim/nepal-qr/records/{id}            one record incl. decoded payload
 *   GET /sim/nepal-qr/txns/{reference}        the created transaction
 */
@RestController
@RequestMapping("/sim/nepal-qr")
public class InspectionController {

    private final NepalQrStore store;

    public InspectionController(NepalQrStore store) {
        this.store = store;
    }

    @GetMapping("/records")
    public List<SimRecord> records(@RequestParam(required = false) String reference) {
        return store.records(reference);
    }

    @GetMapping("/records/{id}")
    public ResponseEntity<?> record(@PathVariable String id) {
        return store.findRecord(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/txns/{reference}")
    public ResponseEntity<?> txn(@PathVariable String reference) {
        return store.findTxn(reference)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
