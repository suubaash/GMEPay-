package com.gme.pay.merchant.persistence;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.merchant.domain.Merchant;
import com.gme.pay.merchant.domain.MerchantRepository;
import com.gme.pay.merchant.web.MerchantResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-backed integration test for the MongoDB merchant store (17.7-G01).
 *
 * <p>Tagged {@code docker}: excluded from the local {@code test} task (this
 * machine has no Docker) and executed by the {@code integrationTest} task on
 * CI ubuntu runners. {@code disabledWithoutDocker = true} skips the class
 * gracefully wherever no Docker engine is available.
 *
 * <p>Covers the full path through the real Mongo store:
 * <ul>
 *   <li>{@code spring.data.mongodb.uri} (from the container) activates
 *       {@code MongoPersistenceConfig} and makes
 *       {@link MongoBackedMerchantRepository} the {@code @Primary} port impl</li>
 *   <li>upsert is idempotent (QR code is the natural {@code _id})</li>
 *   <li>{@code GET /v1/merchants/{qr}} is served from Mongo</li>
 *   <li>unknown QR preserves the 404 + canonical {@link ApiError} semantics</li>
 *   <li>the unique {@code qrCode} index exists on the {@code merchants} collection</li>
 * </ul>
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantMongoStoreIntegrationTest {

    private static final String QR = "QR00000000000000IT1A";

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("merchant"));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MongoBackedMerchantRepository mongoBackedMerchantRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollection() {
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(),
                MerchantDocument.class);
    }

    @Test
    void mongoStoreIsThePrimaryPortImplementation() {
        assertInstanceOf(MongoBackedMerchantRepository.class, merchantRepository,
                "With spring.data.mongodb.uri set, the Mongo adapter must win over the in-memory store");
    }

    @Test
    void upsertThenGet_servedFromMongo() {
        mongoBackedMerchantRepository.upsert(new Merchant(
                "M0000000IT1", QR, "Container Mart", "RETAIL", "DOMESTIC", "ACTIVE", true));

        ResponseEntity<MerchantResponse> response =
                restTemplate.getForEntity("/v1/merchants/" + QR, MerchantResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        MerchantResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("M0000000IT1", body.merchantId());
        assertEquals(QR, body.qrCodeId());
        assertEquals("Container Mart", body.name());
        assertEquals("RETAIL", body.merchantType());
        assertEquals("DOMESTIC", body.feeType());
        assertEquals("ACTIVE", body.status());
        assertTrue(body.active());
    }

    @Test
    void upsertSameQrTwice_isIdempotent_andGetReflectsLatest() {
        mongoBackedMerchantRepository.upsert(new Merchant(
                "M0000000IT1", QR, "Old Name", "RETAIL", "DOMESTIC", "ACTIVE", true));
        mongoBackedMerchantRepository.upsert(new Merchant(
                "M0000000IT1", QR, "New Name", "RETAIL", "CROSSBORDER", "SUSPENDED", false));

        long count = mongoTemplate.count(
                new org.springframework.data.mongodb.core.query.Query(), MerchantDocument.class);
        assertEquals(1, count, "Re-upserting the same QR code must not duplicate documents");

        ResponseEntity<MerchantResponse> response =
                restTemplate.getForEntity("/v1/merchants/" + QR, MerchantResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        MerchantResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("New Name", body.name());
        assertEquals("CROSSBORDER", body.feeType());
        assertEquals("SUSPENDED", body.status());
        assertFalse(body.active());
    }

    @Test
    void unknownQr_returns404WithCanonicalErrorEnvelope() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity(
                "/v1/merchants/QR_DOES_NOT_EXIST___", ApiError.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND.name(), body.code());
        assertFalse(body.retryable());
        assertNotNull(body.requestId());
        assertTrue(body.message().contains("QR_DOES_NOT_EXIST___"));
    }

    @Test
    void uniqueQrCodeIndexExistsOnMerchantsCollection() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(MerchantDocument.class).getIndexInfo();

        Optional<IndexInfo> qrIndex = indexes.stream()
                .filter(i -> MongoPersistenceConfig.QR_CODE_INDEX.equals(i.getName()))
                .findFirst();

        assertTrue(qrIndex.isPresent(),
                "Expected index '" + MongoPersistenceConfig.QR_CODE_INDEX + "' on merchants, found: "
                        + indexes.stream().map(IndexInfo::getName).toList());
        assertTrue(qrIndex.get().isUnique(), "qrCode index must be unique");
        assertTrue(qrIndex.get().getIndexFields().stream()
                        .anyMatch(f -> "qrCode".equals(f.getKey())),
                "Index must cover the qrCode lookup key");
    }
}
