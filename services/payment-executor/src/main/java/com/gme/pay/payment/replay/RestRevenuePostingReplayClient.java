package com.gme.pay.payment.replay;

import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * HTTP implementation of {@link RevenuePostingReplayPort} (gap <b>T2-5</b>): re-POSTs a stored payload to the
 * revenue-ledger endpoint the original posting was aimed at, and classifies the answer.
 *
 * <p>The four endpoints match {@code RestRevenueLedgerClient} exactly — the same paths, keyed on the same
 * posting types — because a replay must go where the lost posting was going. They are all idempotent on their
 * reference/txnRef, which is what makes the replay safe: {@code 201} means the posting was created,
 * {@code 200}/{@code 204} means revenue-ledger already had it, and both mean the revenue is on the books.
 *
 * <p><b>Never throws.</b> Every failure becomes a classified {@link RevenuePostingReplayOutcome}, because the
 * replay service has to make a scheduling decision from it, not handle an exception.
 */
@Component
public class RestRevenuePostingReplayClient implements RevenuePostingReplayPort {

    private static final Logger log = LoggerFactory.getLogger(RestRevenuePostingReplayClient.class);

    /** posting type → revenue-ledger path. Mirrors {@code RestRevenueLedgerClient}'s constants. */
    private static final Map<String, String> PATHS = Map.of(
            RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, "/v1/revenue/capture",
            RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, "/v1/journals/rounding-residual",
            RevenuePostingFailureStore.TYPE_COMMISSION_SPLIT, "/v1/revenue/commission-split",
            RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL, "/v1/journals/reversal");

    /** Cap on the excerpt of a rejection body stamped onto {@code last_error} (column is 1024). */
    private static final int BODY_EXCERPT = 400;

    private final RestClient restClient;

    /** Production wiring; {@code @Autowired} is required because this component has a second constructor. */
    @Autowired
    public RestRevenuePostingReplayClient(
            RestClient.Builder builder,
            @Value("${gmepay.revenue-ledger.base-url:http://revenue-ledger:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Test constructor taking a pre-built client (e.g. bound to a {@code MockRestServiceServer}). */
    public RestRevenuePostingReplayClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public RevenuePostingReplayOutcome replay(String postingType, String jsonPayload) {
        String path = PATHS.get(postingType);
        if (path == null) {
            // A posting type the DB CHECK allows but this client does not know: never retried, always
            // surfaced. Silently skipping it would leave a PENDING row nothing ever touches.
            return RevenuePostingReplayOutcome.unreplayable(
                    "no replay endpoint is mapped for postingType=" + postingType);
        }
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return RevenuePostingReplayOutcome.unreplayable(
                    "no request payload was captured, so the posting cannot be reconstructed");
        }
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(path)
                    // The stored payload is already JSON; sending it as a String with an explicit
                    // content type avoids re-serialising (and therefore possibly altering) it.
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonPayload)
                    .retrieve()
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            return status == HttpStatus.CREATED.value()
                    ? RevenuePostingReplayOutcome.posted(status)
                    : RevenuePostingReplayOutcome.alreadyPresent(status);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String detail = "HTTP " + status + " " + excerpt(ex.getResponseBodyAsString());
            if (isRetryableStatus(status)) {
                return RevenuePostingReplayOutcome.transientFailure(status, detail);
            }
            // A 4xx on a replay means revenue-ledger has judged this exact body invalid. It will judge it
            // invalid again, so the row is poisoned now and a human is told, rather than in eight hours.
            log.warn("revenue posting replay to {} was REJECTED ({}): {}", path, status, detail);
            return RevenuePostingReplayOutcome.permanentRejection(status, detail);
        } catch (Exception ex) {
            // Transport: connection refused, timeout, DNS. Worth another go.
            return RevenuePostingReplayOutcome.transientFailure(0, ex.toString());
        }
    }

    /**
     * 5xx is the server's problem, 408/429 are explicit "come back later" answers. Every other 4xx is a
     * verdict on the request itself.
     */
    private static boolean isRetryableStatus(int status) {
        return status >= 500
                || status == HttpStatus.REQUEST_TIMEOUT.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value();
    }

    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        return body.length() <= BODY_EXCERPT ? body : body.substring(0, BODY_EXCERPT) + "...";
    }
}
