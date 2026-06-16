package com.gme.sim.merchant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Self-contained transparency tracer for this simulator (standalone build, so it can't share
 * lib-errors' auto-config). When {@code gmepay.trace.enabled=true} the sim self-reports its
 * inbound HTTP (and outbound calls made via the autoconfigured RestClient builder) to the
 * trace-console {@code /ingest} endpoint, so it appears in the dashboard like any MSA.
 * Off by default; fully fail-open.
 */
@Configuration
@ConditionalOnProperty(prefix = "gmepay.trace", name = "enabled", havingValue = "true")
public class SimTrace implements DisposableBean {

    static final String CALLER_HEADER = "X-Gme-Trace-Caller";

    private final String self;
    private final URI ingest;
    private final HttpClient http;
    private final ThreadPoolExecutor exec;

    public SimTrace(@Value("${spring.application.name:sim}") String name,
                    @Value("${gmepay.trace.ingest-url:http://localhost:7099/ingest}") String url) {
        this.self = name;
        this.ingest = URI.create(url);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();
        this.exec = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                r -> { Thread t = new Thread(r, "sim-trace"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> simTraceInbound() {
        OncePerRequestFilter f = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String path = req.getRequestURI();
                if (skip(path)) { chain.doFilter(req, res); return; }
                long t0 = System.nanoTime();
                try { chain.doFilter(req, res); }
                finally {
                    String caller = req.getHeader(CALLER_HEADER);
                    report(caller == null || caller.isBlank() ? "external" : caller,
                            self, req.getMethod(), path, res.getStatus(), (System.nanoTime() - t0) / 1_000_000);
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(f);
        reg.setOrder(Ordered.LOWEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        reg.setName("simTraceInbound");
        return reg;
    }

    @Bean
    public RestClientCustomizer simTraceOutbound() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            URI uri = request.getURI();
            if (skip(uri.getPath())) return execution.execute(request, body);
            try { request.getHeaders().add(CALLER_HEADER, self); } catch (RuntimeException ignored) { }
            long t0 = System.nanoTime();
            int status = 0;
            try {
                var resp = execution.execute(request, body);
                status = resp.getStatusCode().value();
                return resp;
            } finally {
                report(self, calleeFor(uri), request.getMethod().name(), uri.getPath(),
                        status, (System.nanoTime() - t0) / 1_000_000);
            }
        });
    }

    private void report(String caller, String callee, String method, String path, int status, long ms) {
        try {
            String json = "{\"caller\":" + q(caller) + ",\"callee\":" + q(callee)
                    + ",\"method\":" + q(method) + ",\"path\":" + q(path)
                    + ",\"status\":" + status + ",\"latencyMs\":" + ms + ",\"source\":\"ingest\"}";
            exec.execute(() -> send(json));
        } catch (RuntimeException ignored) { }
    }

    private void send(String json) {
        try {
            http.send(HttpRequest.newBuilder(ingest).timeout(Duration.ofMillis(800))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) { }
    }

    private static final Map<Integer, String> PORTS = Map.of(
            8084, "payment-executor", 8090, "scheme-adapter", 9101, "sim-rate-provider",
            9102, "sim-scheme", 9103, "sim-wallet", 9104, "sim-merchant", 9105, "sim-gmeremit");

    private static String calleeFor(URI uri) {
        String m = PORTS.get(uri.getPort());
        if (m != null) return m;
        String h = uri.getHost();
        return h == null ? "unknown" : h + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private static boolean skip(String p) {
        return p == null || p.isEmpty() || p.startsWith("/actuator") || p.startsWith("/v3/api-docs")
                || p.startsWith("/swagger") || p.startsWith("/favicon") || p.equals("/error")
                || p.startsWith("/webjars") || p.equals("/ingest") || p.endsWith(".js")
                || p.endsWith(".css") || p.endsWith(".html") || p.equals("/");
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.append('"').toString();
    }

    @Override
    public void destroy() { exec.shutdownNow(); }
}
