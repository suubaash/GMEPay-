package com.gme.sim.nepalqr.controller;

import com.gme.sim.nepalqr.model.NepalQrStore;
import com.gme.sim.nepalqr.model.SimRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and persists a {@link SimRecord} for every inbound request/response.
 * KST timestamps to match the sim-scheme convention.
 */
@Component
public class Recorder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_KST =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final NepalQrStore store;

    public Recorder(NepalQrStore store) {
        this.store = store;
    }

    /** Start a record; caller fills in response fields then calls {@link #save}. */
    public SimRecord begin(String endpoint, HttpServletRequest http, String rawBody) {
        SimRecord r = new SimRecord();
        r.id = "REC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        r.endpoint = endpoint;
        r.receivedAt = ISO_KST.format(ZonedDateTime.now(KST));
        r.relevantRequestHeaders = relevantHeaders(http);
        r.rawRequestBody = rawBody;
        return r;
    }

    public void save(SimRecord r, int status, Object responseBody) {
        r.responseStatus = status;
        r.responseBody = responseBody;
        store.save(r);
    }

    private Map<String, String> relevantHeaders(HttpServletRequest http) {
        Map<String, String> h = new LinkedHashMap<>();
        if (http == null) return h;
        putIfPresent(h, http, "Authorization");
        putIfPresent(h, http, "X-KhaltiNonce");
        putIfPresent(h, http, "Content-Type");
        h.put("X-Source-IP", http.getRemoteAddr());
        return h;
    }

    private void putIfPresent(Map<String, String> h, HttpServletRequest http, String name) {
        String v = http.getHeader(name);
        if (v != null) h.put(name, v);
    }
}
