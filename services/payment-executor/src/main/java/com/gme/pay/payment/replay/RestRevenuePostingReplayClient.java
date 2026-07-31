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
            // A 4xx that survived the check above means revenue-ledger has judged this exact body invalid.
            // It will judge it invalid again, so the row is poisoned now and a human is told, rather than in
            // eight hours.
            log.warn("revenue posting replay to {} was REJECTED ({}): {}", path, status, detail);
            return RevenuePostingReplayOutcome.permanentRejection(status, detail);
        } catch (Exception ex) {
            // Transport: connection refused, timeout, DNS. Worth another go.
            return RevenuePostingReplayOutcome.transientFailure(0, ex.toString());
        }
    }

    /**
     * The four 4xx codes Spring MVC raises from <b>routing and content negotiation</b> — before the handler
     * method ever sees the request body.
     *
     * <ul>
     *   <li>{@code 404} — {@code NoHandlerFoundException}: the path is not mapped on the deployed server.</li>
     *   <li>{@code 405} — {@code HttpRequestMethodNotSupportedException}: the path is mapped, the verb is not.</li>
     *   <li>{@code 415} — {@code HttpMediaTypeNotSupportedException}: request-side negotiation failed.</li>
     *   <li>{@code 406} — {@code HttpMediaTypeNotAcceptableException}: response-side negotiation failed.</li>
     * </ul>
     *
     * <p>This is the line, and it is a real one rather than a convenient one: these four are produced by the
     * dispatcher, so they are statements about <b>which code is deployed</b>, not about the payload. A 400 is
     * the opposite — {@code HttpMessageNotReadableException} or bean validation, both of which have read the
     * body and judged it. 401/403 are excluded too, despite also being pre-handler: they are an
     * authentication/authorisation verdict, and re-presenting rejected credentials on a schedule is a bad
     * pattern regardless of whether the row survives it.
     */
    private static boolean isDeploymentMismatch(int status) {
        return status == HttpStatus.NOT_FOUND.value()
                || status == HttpStatus.METHOD_NOT_ALLOWED.value()
                || status == HttpStatus.NOT_ACCEPTABLE.value()
                || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value();
    }

    /**
     * 5xx is the server's problem, 408/429 are explicit "come back later" answers, and
     * {@linkplain #isDeploymentMismatch(int) four codes} are the server saying it cannot route or negotiate
     * this call at all. Every other 4xx is a verdict on the request itself.
     *
     * <h2>Why 406 moved (gap T3-12)</h2>
     *
     * <p>It used to be permanent, and that classification did real damage. Revenue-ledger's two journal
     * endpoints returned the domain {@code Journal} type, which Jackson could not serialise, so Spring could
     * find no converter and answered <b>406 on every call</b>. Every {@code ROUNDING_RESIDUAL} and
     * {@code REVERSAL_JOURNAL} posting was therefore poisoned on the <b>first</b> sweep — and POISON is
     * terminal — for a bug in <em>our own</em> server that was fixed the same week. The rows were unrecoverable
     * by any code path in the service.
     *
     * <p>The classification was wrong on its own terms, not merely unlucky. A 406 is content negotiation: the
     * server cannot produce a representation acceptable to the client. It says nothing whatsoever about
     * whether the posting is valid. Between two services we deploy ourselves, over a contract we own, it can
     * only mean a version mismatch — which is exactly the condition a retry after a deploy fixes, and exactly
     * the condition burying the row makes worse.
     *
     * <p><b>This is not "retry everything".</b> The attempt budget still bounds it: eight attempts of
     * exponential backoff, so a genuinely permanent 406 still reaches POISON, just after a deploy window
     * instead of within one sweep. A 400, 409 or 422 — the codes that mean revenue-ledger read the body and
     * refused it — still terminate on the first sweep, because for those the fast answer is the right one.
     *
     * <p>Retryability buys hours; it is not a substitute for
     * {@link RevenuePostingRequeueService}. A server defect discovered a week later still needs the requeue
     * path, because no status classification can be right about every future bug. The two changes are
     * complementary and both were needed.
     */
    private static boolean isRetryableStatus(int status) {
        return status >= 500
                || status == HttpStatus.REQUEST_TIMEOUT.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value()
                || isDeploymentMismatch(status);
    }

    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        return body.length() <= BODY_EXCERPT ? body : body.substring(0, BODY_EXCERPT) + "...";
    }
}
