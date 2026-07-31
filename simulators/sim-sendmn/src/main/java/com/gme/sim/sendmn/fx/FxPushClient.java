package com.gme.sim.sendmn.fx;

import com.gme.sim.sendmn.config.SimSendmnProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes the sim's registered rate to the partner's FX-rate registration endpoint
 * (scheme-adapter-sendmn {@code POST /partner-hosted/fx-rate}) — the one direction
 * where the real SendMN calls US (doc §9). Field names mirror the adapter's
 * {@code FxRateRegistrationRequest}. Off by default; auto-push on startup when
 * {@code gmepay.sim.sendmn.fx-push.enabled=true}, or on demand via
 * POST /sim/fx-rate/push (best-effort — the adapter may not be running).
 */
@Component
public class FxPushClient {

    private static final Logger log = LoggerFactory.getLogger(FxPushClient.class);

    private final SimSendmnProperties props;
    private final FxState fxState;
    private final RestClient restClient;

    public FxPushClient(SimSendmnProperties props, FxState fxState, RestClient.Builder builder) {
        this.props = props;
        this.fxState = fxState;
        this.restClient = builder.build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pushOnStartupIfEnabled() {
        if (!props.getFxPush().isEnabled()) {
            return;
        }
        try {
            log.info("fx-push on startup -> {}: {}", props.getFxPush().getUrl(), push());
        } catch (Exception e) {
            log.warn("fx-push on startup failed (partner not up?): {}", e.getMessage());
        }
    }

    /** Registers the current rate with the partner; returns the partner's raw response. */
    public String push() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FX_TICKER_NO", fxState.fxTickerNo());
        body.put("NOTICE_DATE", fxState.noticeDate());
        body.put("RATE", fxState.rate());
        body.put("LOCAL_CUR_CODE", "MNT");
        body.put("SETTLEMENT_CUR_CODE", "USD");
        return restClient.post()
                .uri(props.getFxPush().getUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
