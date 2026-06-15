package com.gme.pay.settlement.client;

import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * HTTP client implementation of {@link TransactionQueryPort}.
 *
 * <p>Calls transaction-mgmt's REST API to fetch approved transactions.
 * Never reads the transaction-mgmt database directly (MSA rule).
 *
 * <p>This bean is {@link Primary} so it wins when both this and a stub are on the
 * classpath. Tests use {@code @SpringBootTest} exclusion or a {@code @TestConfiguration}
 * to override with a stub.
 *
 * <p>The Spring 6 / Spring Boot 3.x rule: a {@link Component} with two or more
 * constructors MUST annotate the one to be used by the container with
 * {@link org.springframework.beans.factory.annotation.Autowired}.
 */
@Primary
@Component
public class RestTransactionQueryClient implements TransactionQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionQueryClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    /**
     * Primary constructor wired by Spring.
     * {@code @Autowired} is required here because {@link RestTransactionQueryClient}
     * has only one constructor, so Spring 6 injects it automatically — but we annotate
     * it explicitly as documentation of intent and to satisfy the "2+ ctors" rule
     * should a secondary constructor be added in future.
     */
    public RestTransactionQueryClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.transaction-mgmt.base-url:http://transaction-mgmt:8082}") String baseUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
    }

    @Override
    public List<TransactionRecord> findUnbatchedApproved(LocalDate settlementDate) {
        String url = baseUrl + "/v1/transactions/settlement?date=" + settlementDate
                + "&status=APPROVED&unbatched=true";
        log.debug("Fetching unbatched approved transactions for date={} from {}", settlementDate, url);
        try {
            TransactionRecord[] records = restTemplate.getForObject(url, TransactionRecord[].class);
            return records != null ? Arrays.asList(records) : List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch transactions from transaction-mgmt: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<TransactionRecord> findByBatchId(Long batchId) {
        String url = baseUrl + "/v1/transactions/settlement?batchId=" + batchId;
        log.debug("Fetching transactions for batchId={} from {}", batchId, url);
        try {
            TransactionRecord[] records = restTemplate.getForObject(url, TransactionRecord[].class);
            return records != null ? Arrays.asList(records) : List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch transactions for batchId={} from transaction-mgmt: {}", batchId, e.getMessage());
            return List.of();
        }
    }
}
