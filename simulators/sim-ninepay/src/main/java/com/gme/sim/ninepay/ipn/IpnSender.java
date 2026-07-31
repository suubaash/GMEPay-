package com.gme.sim.ninepay.ipn;

import com.gme.sim.ninepay.config.NinepaySimConfig;
import com.gme.sim.ninepay.model.NinepayStore;
import com.gme.sim.ninepay.model.TransferRecord;
import com.gme.sim.ninepay.sign.SimSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds and pushes 9Pay IPN callbacks to the partner's registered {@code ipn_url}
 * ({@code sim.ninepay.ipn-url} — default the adapter's {@code POST /scheme/ipn} on :8096).
 *
 * <p>Payload per spec 3.5: {@code trans_id} (field-name drift — everywhere else says
 * {@code transaction_id}), signature with the SIM'S key over
 * {@code request_id|partner_id|trans_id|request_amount|fee|transfer_amount|type|status|created_at}
 * — {@code message}, {@code approved_at} and {@code code} are NOT part of the signed
 * string. Delivery is best-effort (short timeouts); every built payload lands in the
 * store's IPN outbox regardless, so tests and humans can inspect exactly what was pushed
 * ({@code GET /sim/ipns}).</p>
 */
@Component
public class IpnSender {

    private static final Logger log = LoggerFactory.getLogger(IpnSender.class);
    private static final ZoneId NINEPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NinepaySimConfig config;
    private final NinepayStore store;
    private final SimSigner signer;
    private final RestClient restClient;

    public IpnSender(NinepaySimConfig config, NinepayStore store, SimSigner signer) {
        this.config = config;
        this.store = store;
        this.signer = signer;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Sends one IPN for the record with the given status/code (code {@code 000} success,
     * {@code 001–007} failure reasons, {@code 008} held, {@code 009} bank reversal).
     */
    public void send(TransferRecord record, String partnerId, String status, String code, String message) {
        String createdAt = record.getCreatedAt();
        String approvedAt = TIME_FORMAT.format(ZonedDateTime.now(NINEPAY_ZONE));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", record.getRequestId());
        payload.put("partner_id", partnerId);
        payload.put("trans_id", record.getTransactionId());
        payload.put("request_amount", record.getAmountVnd());
        payload.put("fee", record.getFeeVnd());
        payload.put("transfer_amount", record.getTransferAmountVnd());
        payload.put("type", "TRANSFER_BANK");
        payload.put("status", status);
        payload.put("created_at", createdAt);
        payload.put("signature", signer.sign(SimSigner.canonical(
                record.getRequestId(), partnerId, record.getTransactionId(),
                record.getAmountVnd(), record.getFeeVnd(), record.getTransferAmountVnd(),
                "TRANSFER_BANK", status, createdAt)));
        payload.put("message", message);
        payload.put("approved_at", approvedAt);
        payload.put("code", code);

        boolean delivered = false;
        String error = null;
        String url = config.getIpnUrl();
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            delivered = true;
            log.debug("IPN delivered to {}: request_id={} status={} code={}",
                    url, record.getRequestId(), status, code);
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("IPN delivery to {} failed (payload kept in outbox): {}", url, e.getMessage());
        }
        store.recordIpn(new NinepayStore.IpnOutboxEntry(url, payload, delivered, error));
    }
}
