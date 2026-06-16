package com.gme.pay.trace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget reporter that POSTs trace events to the trace-console {@code /ingest}
 * endpoint. Built on the JDK {@link HttpClient} (no extra deps). Reporting is fully
 * non-blocking and fail-open: events are handed to a small bounded executor and dropped if
 * the queue is full or the console is down — tracing must never add latency or fail a
 * business request.
 */
public class TraceReporter implements AutoCloseable {

    private final String self;
    private final URI ingestUri;
    private final HttpClient client;
    private final ThreadPoolExecutor exec;

    public TraceReporter(String serviceName, String ingestUrl, int queueCapacity) {
        this.self = (serviceName == null || serviceName.isBlank()) ? "unknown" : serviceName;
        this.ingestUri = URI.create(ingestUrl);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();
        this.exec = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(16, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "gme-trace-reporter");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()); // drop, never block the caller
    }

    /** This process's service name (used as caller on outbound, callee on inbound). */
    public String self() { return self; }

    /**
     * Record one edge. {@code category} may be null (the console will categorise it).
     * Any failure is swallowed.
     */
    public void report(String caller, String callee, String method, String path,
                       int status, long latencyMs, String category, String detail) {
        try {
            TraceJson j = new TraceJson()
                    .str("caller", caller)
                    .str("callee", callee)
                    .str("method", method)
                    .str("path", path)
                    .num("status", status)
                    .num("latencyMs", latencyMs)
                    .str("source", "ingest");
            if (category != null) j.str("category", category);
            if (detail != null) j.str("detail", detail);
            final String body = j.end();
            exec.execute(() -> send(body));
        } catch (RuntimeException ignored) {
            // never propagate into the business path
        }
    }

    /** Record a non-HTTP internal event (scheduler tick, outbox drain, async job). */
    public void event(String name, String detail) {
        report(self, "-", "EVENT", name, 200, 0, "Schedulers", detail);
    }

    private void send(String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(ingestUri)
                    .timeout(Duration.ofMillis(800))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // console down / network error — drop silently
        }
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
