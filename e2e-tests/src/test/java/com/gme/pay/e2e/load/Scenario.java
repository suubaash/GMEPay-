package com.gme.pay.e2e.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One money-path journey the harness can drive, and the two implementations of it.
 *
 * <p>Both talk to the fleet the same way {@code SchemeFleet} does — plain {@code java.net.http}, and
 * every request carries {@code X-Gme-Internal} (T0-2 / T0-5), because several surfaces on this path are
 * gated and answer 401 without it while the ungated ones simply ignore the header. Reusing that
 * convention rather than inventing a second one is deliberate: there is exactly one way to talk to
 * these services in this repo.
 */
interface Scenario {

    /** Executes one full journey. Must never throw — a failure is an {@link Outcome}, not an exception. */
    Outcome runOnce(int iteration);

    /** Endpoints this scenario needs answering before the run starts, as full URLs to probe. */
    List<String> preflightUrls();

    String name();

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    /** Header the {@code com.gme.pay.internalauth} gate reads (kept literal, as in {@code SchemeFleet}). */
    String INTERNAL_HEADER = "X-Gme-Internal";

    ObjectMapper JSON = new ObjectMapper();

    /**
     * A run-scoped unique suffix source for {@code partner_txn_ref} / {@code userRef}.
     *
     * <p>Non-negotiable for this harness: {@code POST /v1/payments/authorize} is <b>idempotent per
     * (partner, partner_txn_ref)</b>, so a reused ref would replay the first authorization instead of
     * creating work — the run would report beautiful latencies for a code path that does nothing but a
     * primary-key lookup. Same reasoning for the wallet path's idempotency key.
     */
    AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    static HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /** POST helper. Returns the raw response; the caller classifies it. */
    static HttpResponse<String> post(HttpClient http, String url, String json, String secret, Duration timeout)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header(INTERNAL_HEADER, secret)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Wraps an {@link IOException} / timeout into an {@link Outcome}.
     *
     * <p>{@code HttpTimeoutException} is a subclass of {@code IOException}, so the two are separated by
     * type here rather than by message — a timeout is the single most interesting failure mode of a
     * capacity run ("it did not fail, it stopped answering") and must not be flattened into a generic
     * transport error.
     */
    static Outcome transportFailure(Exception e, long latencyNs) {
        if (e instanceof java.net.http.HttpTimeoutException) {
            return Outcome.error("TIMEOUT", 0, latencyNs);
        }
        if (e instanceof java.net.ConnectException) {
            return Outcome.error("CONNECTION_REFUSED", 0, latencyNs);
        }
        return Outcome.error("TRANSPORT_" + e.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT),
                0, latencyNs);
    }

    // -------------------------------------------------------------------------

    /**
     * <b>wallet-pay</b> — a wallet scans a merchant QR and pays: one {@code POST /v1/pay} on
     * payment-executor, which fans out to merchant-qr-data (resolve + ACTIVE), the scheme adapter
     * (authorize + commit against the sim), transaction-mgmt (persist) and revenue-ledger (fee journal).
     *
     * <p>One HTTP call from the client, five services of work behind it — which is what makes it the
     * right shape for a capacity run: the measured latency is the whole cascade, and the first pool to
     * saturate anywhere in it shows up as this number growing.
     */
    final class WalletPay implements Scenario {
        private final HttpClient http;
        private final LoadOptions opts;

        WalletPay(LoadOptions opts) {
            this.opts = opts;
            this.http = client(Duration.ofSeconds(3));
        }

        @Override
        public String name() {
            return "wallet-pay";
        }

        @Override
        public List<String> preflightUrls() {
            return List.of(opts.paymentExecutorBaseUrl + "/v1/_probe");
        }

        @Override
        public Outcome runOnce(int iteration) {
            String userRef = "load-" + SEQUENCE.incrementAndGet();
            String body = """
                    {"qrPayload":%s,"amountKrw":%s,"partner":%s,"userRef":%s,"currency":%s}"""
                    .formatted(quote(opts.qrPayload), quote(opts.amount), quote(opts.partnerCode),
                            quote(userRef), quote(opts.currency));
            long started = System.nanoTime();
            try {
                HttpResponse<String> resp = post(http, opts.paymentExecutorBaseUrl + "/v1/pay",
                        body, opts.internalSecret, opts.requestTimeout);
                long elapsed = System.nanoTime() - started;
                Outcome outcome = ResponseCodes.classify(resp.statusCode(), resp.body(), elapsed);
                return outcome.kind() == Outcome.Kind.OK
                        ? Outcome.ok(elapsed, new long[] {elapsed}, new String[] {"pay"})
                        : outcome;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.error("INTERRUPTED", 0, System.nanoTime() - started);
            } catch (Exception e) {
                return transportFailure(e, System.nanoTime() - started);
            }
        }
    }

    /**
     * <b>authorize-confirm</b> — the orchestrated two-phase flow of {@code SETTLEMENT_FLOW_SPEC} §4/§7.1:
     *
     * <pre>
     *   POST rate-fx      /v1/quotes/partner        → a TTL-locked quote_id
     *   POST payment-exec /v1/payments/authorize    → reserves partner float, creates PENDING txn
     *   POST payment-exec /v1/payments/{id}/confirm → the irreversible scheme submit + float capture
     * </pre>
     *
     * <p>Measured end-to-end AND per step, because the three steps stress different ceilings: the quote
     * hits rate-fx's snapshot store, the authorize takes a prefunding row lock, and only the confirm
     * makes the scheme call. A capacity run that reported one blended number could not tell you which
     * of the three moved.
     *
     * <p>Each iteration mints a fresh {@code partner_txn_ref} — see {@link Scenario#SEQUENCE} for why
     * that is load-bearing rather than tidy.
     */
    final class AuthorizeConfirm implements Scenario {
        private final HttpClient http;
        private final LoadOptions opts;

        AuthorizeConfirm(LoadOptions opts) {
            this.opts = opts;
            this.http = client(Duration.ofSeconds(3));
        }

        @Override
        public String name() {
            return "authorize-confirm";
        }

        @Override
        public List<String> preflightUrls() {
            return List.of(opts.rateFxBaseUrl + "/v1/_probe",
                    opts.paymentExecutorBaseUrl + "/v1/_probe");
        }

        @Override
        public Outcome runOnce(int iteration) {
            long seq = SEQUENCE.incrementAndGet();
            String partnerTxnRef = "LOAD-" + seq;
            long started = System.nanoTime();
            long afterQuote;
            long afterAuthorize;

            // ---- step 1: quote (rate-fx resolves the partner's own margins; the caller cannot price) ----
            String quoteBody = """
                    {"partnerCode":%s,"schemeId":%s,"direction":%s,"targetPayout":%s,"payoutCurrency":%s}"""
                    .formatted(quote(opts.partnerCode), quote(opts.schemeId), quote(opts.direction),
                            opts.amount, quote(opts.currency));
            String quoteId;
            try {
                HttpResponse<String> resp = post(http, opts.rateFxBaseUrl + "/v1/quotes/partner",
                        quoteBody, opts.internalSecret, opts.requestTimeout);
                afterQuote = System.nanoTime();
                if (resp.statusCode() / 100 != 2) {
                    return withStep(ResponseCodes.classify(resp.statusCode(), resp.body(), afterQuote - started),
                            "quote");
                }
                JsonNode quoteNode = JSON.readTree(resp.body());
                quoteId = quoteNode.path("quoteId").asText("");
                if (quoteId.isBlank()) {
                    return Outcome.error("QUOTE_NO_ID", resp.statusCode(), afterQuote - started);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.error("INTERRUPTED", 0, System.nanoTime() - started);
            } catch (Exception e) {
                return withStep(transportFailure(e, System.nanoTime() - started), "quote");
            }

            // ---- step 2: authorize (float reserve; nothing irreversible yet) ----
            String authorizeBody = """
                    {"quote_id":%s,"merchant_qr":%s,"direction":%s,"scheme_id":%s,"customer_ref":%s,
                     "partner_txn_ref":%s,"collection_amount":%s,"collection_currency":%s}"""
                    .formatted(quote(quoteId), quote(opts.qrPayload), quote(opts.direction),
                            quote(opts.schemeId), quote("load-cust-" + seq), quote(partnerTxnRef),
                            quote(opts.amount), quote(opts.currency));
            String authId;
            try {
                HttpResponse<String> resp = post(http, opts.paymentExecutorBaseUrl + "/v1/payments/authorize",
                        authorizeBody, opts.internalSecret, opts.requestTimeout);
                afterAuthorize = System.nanoTime();
                if (resp.statusCode() / 100 != 2) {
                    return withStep(ResponseCodes.classify(resp.statusCode(), resp.body(),
                            afterAuthorize - started), "authorize");
                }
                authId = JSON.readTree(resp.body()).path("auth_id").asText("");
                if (authId.isBlank()) {
                    return Outcome.error("AUTHORIZE_NO_ID", resp.statusCode(), afterAuthorize - started);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.error("INTERRUPTED", 0, System.nanoTime() - started);
            } catch (Exception e) {
                return withStep(transportFailure(e, System.nanoTime() - started), "authorize");
            }

            // ---- step 3: confirm (the scheme submit — the only irreversible step) ----
            String confirmBody = """
                    {"wallet_charge_ref":%s}""".formatted(quote("load-wallet-" + seq));
            try {
                HttpResponse<String> resp = post(http,
                        opts.paymentExecutorBaseUrl + "/v1/payments/" + authId + "/confirm",
                        confirmBody, opts.internalSecret, opts.requestTimeout);
                long finished = System.nanoTime();
                long total = finished - started;
                if (resp.statusCode() / 100 != 2) {
                    return withStep(ResponseCodes.classify(resp.statusCode(), resp.body(), total), "confirm");
                }
                return Outcome.ok(total,
                        new long[] {afterQuote - started, afterAuthorize - afterQuote, finished - afterAuthorize},
                        new String[] {"quote", "authorize", "confirm"});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.error("INTERRUPTED", 0, System.nanoTime() - started);
            } catch (Exception e) {
                return withStep(transportFailure(e, System.nanoTime() - started), "confirm");
            }
        }

        /** Prefixes the failing step onto the code, so "which leg broke" survives into the tally. */
        private static Outcome withStep(Outcome outcome, String step) {
            return new Outcome(outcome.kind(), step + ":" + outcome.code(), outcome.httpStatus(),
                    outcome.latencyNs(), outcome.stepNs(), outcome.stepNames());
        }
    }

    /** Minimal JSON string escaping — the QR payload contains spaces and the refs are ASCII. */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
