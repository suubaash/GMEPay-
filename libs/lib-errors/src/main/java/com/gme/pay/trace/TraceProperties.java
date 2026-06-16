package com.gme.pay.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the GMEPay+ transparency tracer (the {@code /ingest} self-reporting
 * collector). Disabled by default — production must leave it OFF. Enable per-process with
 * {@code GMEPAY_TRACE_ENABLED=true} (or {@code gmepay.trace.enabled=true}).
 *
 * <p>Unlike the tap-proxy model, every instrumented service POSTs its own inbound HTTP,
 * outbound HTTP, Kafka, and scheduler activity to the console, so a service appears in the
 * dashboard simply by running with tracing enabled — no per-hop proxy wiring.
 */
@ConfigurationProperties(prefix = "gmepay.trace")
public class TraceProperties {

    /** Master switch. Off by default. */
    private boolean enabled = false;

    /** Trace-console ingest endpoint. */
    private String ingestUrl = "http://localhost:7099/ingest";

    /** Logical service name in the dashboard. Falls back to spring.application.name. */
    private String serviceName;

    /** Cap on queued reports; excess is dropped (tracing must never back-pressure the app). */
    private int queueCapacity = 2000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getIngestUrl() { return ingestUrl; }
    public void setIngestUrl(String ingestUrl) { this.ingestUrl = ingestUrl; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
}
