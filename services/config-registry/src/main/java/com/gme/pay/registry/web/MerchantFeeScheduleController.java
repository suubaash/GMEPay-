package com.gme.pay.registry.web;

import com.gme.pay.contracts.MerchantFeeScheduleCommand;
import com.gme.pay.contracts.MerchantFeeScheduleView;
import com.gme.pay.registry.scheme.MerchantFeeScheduleService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QR-scheme setup: the configurable GROSS merchant fee schedule by
 * (scheme × merchant type) (V032) — the input to the V031 commission split.
 *
 * <ul>
 *   <li>{@code GET  /v1/schemes/{schemeId}/merchant-fees} — current rows.</li>
 *   <li>{@code PUT  /v1/schemes/{schemeId}/merchant-fees} — bulk replace
 *       (one row per merchant type; empty list clears).</li>
 *   <li>{@code GET  /v1/schemes/{schemeId}/merchant-fees/effective?merchantType=}
 *       — resolved rate (exact type beats the default) for the payment path's
 *       snapshot; {@code resolved=false} when no row applies.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/schemes/{schemeId}/merchant-fees")
public class MerchantFeeScheduleController {

    private final MerchantFeeScheduleService service;

    public MerchantFeeScheduleController(MerchantFeeScheduleService service) {
        this.service = service;
    }

    /** Current merchant-fee rows for the scheme. 404 when the code is unknown. */
    @GetMapping
    public List<MerchantFeeScheduleView> get(@PathVariable String schemeId) {
        return service.currentMerchantFees(schemeId);
    }

    /** Bulk-replace the scheme's merchant-fee set. 404 unknown scheme; 400 on validation failure. */
    @PutMapping
    public List<MerchantFeeScheduleView> replace(
            @PathVariable String schemeId,
            @RequestBody List<MerchantFeeScheduleCommand> fees,
            @RequestHeader(name = "X-Actor", required = false) String actor) {
        return service.replaceMerchantFees(schemeId, fees, actor);
    }

    /**
     * Resolve the effective gross fee rate for {@code merchantType} (exact type
     * beats the scheme default). Lenient: {@code resolved=false} + null rate when
     * nothing applies. The payment path calls this to snapshot the rate onto a
     * transaction at creation.
     */
    @GetMapping("/effective")
    public Map<String, Object> effective(
            @PathVariable String schemeId,
            @RequestParam(required = false) String merchantType) {
        BigDecimal rate = service.resolveRate(schemeId, merchantType).orElse(null);
        return Map.of(
                "schemeId", schemeId,
                "merchantType", merchantType == null ? "" : merchantType,
                "merchantFeePct", rate == null ? "" : rate.toPlainString(),
                "resolved", rate != null);
    }
}
