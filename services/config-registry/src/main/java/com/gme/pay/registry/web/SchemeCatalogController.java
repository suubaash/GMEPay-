package com.gme.pay.registry.web;

import com.gme.pay.registry.scheme.SchemeCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /v1/schemes} — the platform's supported-scheme catalog.
 *
 * <p>Surfaces the master roster of QR payment schemes (ZeroPay live; the corridor
 * schemes on the roadmap) so the Admin UI schemes page and the Slice-7 scheme
 * picker have a data source. Read-only reference data; per-partner enablements are
 * served separately by {@code PartnerSchemeController}.
 */
@RestController
@RequestMapping("/v1/schemes")
public class SchemeCatalogController {

    private final SchemeCatalogService catalog;

    public SchemeCatalogController(SchemeCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<SchemeCatalogResponse> list() {
        return catalog.listSchemes();
    }
}
