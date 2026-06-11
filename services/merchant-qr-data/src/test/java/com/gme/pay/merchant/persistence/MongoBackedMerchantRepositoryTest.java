package com.gme.pay.merchant.persistence;

import com.gme.pay.merchant.domain.Merchant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.FluentQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link MongoBackedMerchantRepository}.
 *
 * <p>Uses a hand-written in-memory {@link MongoRepository} fake (no Mongo,
 * no Testcontainers, no Docker, no Spring context) — fully deterministic and
 * fast. Only the methods the adapter actually delegates to
 * ({@link MerchantMongoRepository#findByQrCode(String)},
 * {@link MerchantMongoRepository#findByMerchantId(String)} and
 * {@code save}) carry real behaviour; the rest of the {@link MongoRepository}
 * surface throws {@link UnsupportedOperationException}.
 *
 * <p>The real-Mongo path (Testcontainers) is covered by
 * {@code MerchantMongoStoreIntegrationTest}, which is tagged {@code docker}
 * and runs only via the {@code integrationTest} task on CI.
 */
class MongoBackedMerchantRepositoryTest {

    private InMemoryMongoFake fake;
    private MongoBackedMerchantRepository adapter;

    @BeforeEach
    void setUp() {
        fake = new InMemoryMongoFake();
        fake.seed(new MerchantDocument(
                "QR-ABC-1", "M001", "QR-ABC-1", "Seoul Mart", "RETAIL", "DOMESTIC", "ACTIVE", true));
        fake.seed(new MerchantDocument(
                "QR-XYZ-9", "M999", "QR-XYZ-9", "Busan Cafe", "FOOD_BEVERAGE", "DOMESTIC", "SUSPENDED", false));
        adapter = new MongoBackedMerchantRepository(fake);
    }

    @Test
    void findByQrCodeId_delegatesToFindByQrCode_andMapsToDomain() {
        Optional<Merchant> result = adapter.findByQrCodeId("QR-ABC-1");

        assertTrue(result.isPresent(), "Expected a hit for seeded QR code");
        Merchant m = result.get();
        assertEquals("M001", m.merchantId());
        assertEquals("QR-ABC-1", m.qrCodeId());
        assertEquals("Seoul Mart", m.name());
        assertEquals("RETAIL", m.merchantType());
        assertEquals("DOMESTIC", m.feeType());
        assertEquals("ACTIVE", m.status());
        assertTrue(m.active());
        assertTrue(m.isOperational());

        // Confirm delegation actually hit findByQrCode and not some other method.
        assertEquals(1, fake.findByQrCodeCalls);
        assertEquals(0, fake.findByMerchantIdCalls);
    }

    @Test
    void findByQrCodeId_missing_returnsEmpty() {
        Optional<Merchant> result = adapter.findByQrCodeId("QR-DOES-NOT-EXIST");

        assertFalse(result.isPresent());
        assertEquals(1, fake.findByQrCodeCalls);
    }

    @Test
    void findByQrCodeId_null_returnsEmpty_andDoesNotHitRepository() {
        Optional<Merchant> result = adapter.findByQrCodeId(null);

        assertFalse(result.isPresent());
        assertEquals(0, fake.findByQrCodeCalls,
                "Adapter must short-circuit on null without delegating");
    }

    @Test
    void findByQrCodeId_suspendedDoc_preservesStatusAndIsNotOperational() {
        Optional<Merchant> result = adapter.findByQrCodeId("QR-XYZ-9");

        assertTrue(result.isPresent());
        Merchant m = result.get();
        assertFalse(m.active());
        assertEquals("SUSPENDED", m.status(),
                "Persisted lifecycle status must survive the round-trip unchanged");
        assertFalse(m.isOperational());
    }

    @Test
    void findByMerchantId_delegatesToFindByMerchantId_andMapsToDomain() {
        Optional<Merchant> result = adapter.findByMerchantId("M001");

        assertTrue(result.isPresent());
        assertEquals("M001", result.get().merchantId());
        assertEquals("QR-ABC-1", result.get().qrCodeId());

        assertEquals(1, fake.findByMerchantIdCalls);
        assertEquals(0, fake.findByQrCodeCalls);
    }

    @Test
    void findByMerchantId_missing_returnsEmpty() {
        Optional<Merchant> result = adapter.findByMerchantId("M-UNKNOWN");

        assertFalse(result.isPresent());
        assertEquals(1, fake.findByMerchantIdCalls);
    }

    @Test
    void findByMerchantId_null_returnsEmpty_andDoesNotHitRepository() {
        Optional<Merchant> result = adapter.findByMerchantId(null);

        assertFalse(result.isPresent());
        assertEquals(0, fake.findByMerchantIdCalls);
    }

    @Test
    void upsert_insertsNewDocumentKeyedByQrCode() {
        Merchant inserted = adapter.upsert(new Merchant(
                "M555", "QR-NEW-5", "Daegu Books", "RETAIL", "DOMESTIC", "ACTIVE", true));

        assertEquals("QR-NEW-5", inserted.qrCodeId());
        assertEquals(1, fake.saveCalls);

        Optional<Merchant> roundTrip = adapter.findByQrCodeId("QR-NEW-5");
        assertTrue(roundTrip.isPresent());
        assertEquals("M555", roundTrip.get().merchantId());
        assertEquals("Daegu Books", roundTrip.get().name());
    }

    @Test
    void upsert_sameQrCodeTwice_replacesInsteadOfDuplicating() {
        adapter.upsert(new Merchant(
                "M777", "QR-UP-7", "Old Name", "RETAIL", "DOMESTIC", "ACTIVE", true));
        adapter.upsert(new Merchant(
                "M777", "QR-UP-7", "New Name", "RETAIL", "CROSSBORDER", "SUSPENDED", false));

        assertEquals(2, fake.saveCalls);
        assertEquals(1, fake.countDocumentsWithQrCode("QR-UP-7"),
                "Upsert by natural id must not create duplicates");

        Merchant current = adapter.findByQrCodeId("QR-UP-7").orElseThrow();
        assertEquals("New Name", current.name());
        assertEquals("CROSSBORDER", current.feeType());
        assertEquals("SUSPENDED", current.status());
        assertFalse(current.active());
    }

    // ---------------------------------------------------------------------
    // Hand-written in-memory MongoRepository fake. Only findByQrCode,
    // findByMerchantId and save carry real behaviour — the rest throws.
    // ---------------------------------------------------------------------
    private static final class InMemoryMongoFake implements MerchantMongoRepository {

        private final Map<String, MerchantDocument> byId = new HashMap<>();
        int findByQrCodeCalls;
        int findByMerchantIdCalls;
        int saveCalls;

        void seed(MerchantDocument doc) {
            byId.put(doc.getId(), doc);
        }

        long countDocumentsWithQrCode(String qr) {
            return byId.values().stream().filter(d -> qr.equals(d.getQrCode())).count();
        }

        @Override
        public Optional<MerchantDocument> findByQrCode(String qr) {
            findByQrCodeCalls++;
            return byId.values().stream().filter(d -> d.getQrCode().equals(qr)).findFirst();
        }

        @Override
        public Optional<MerchantDocument> findByMerchantId(String mid) {
            findByMerchantIdCalls++;
            return byId.values().stream().filter(d -> d.getMerchantId().equals(mid)).findFirst();
        }

        @Override
        public <S extends MerchantDocument> S save(S entity) {
            saveCalls++;
            byId.put(entity.getId(), entity);
            return entity;
        }

        // -- Unused MongoRepository surface ------------------------------------

        @Override public <S extends MerchantDocument> S insert(S entity) { throw nope(); }
        @Override public <S extends MerchantDocument> List<S> insert(Iterable<S> entities) { throw nope(); }
        @Override public <S extends MerchantDocument> List<S> saveAll(Iterable<S> entities) { throw nope(); }
        @Override public Optional<MerchantDocument> findById(String s) { throw nope(); }
        @Override public boolean existsById(String s) { throw nope(); }
        @Override public List<MerchantDocument> findAll() { throw nope(); }
        @Override public List<MerchantDocument> findAllById(Iterable<String> strings) { throw nope(); }
        @Override public long count() { throw nope(); }
        @Override public void deleteById(String s) { throw nope(); }
        @Override public void delete(MerchantDocument entity) { throw nope(); }
        @Override public void deleteAllById(Iterable<? extends String> strings) { throw nope(); }
        @Override public void deleteAll(Iterable<? extends MerchantDocument> entities) { throw nope(); }
        @Override public void deleteAll() { throw nope(); }
        @Override public List<MerchantDocument> findAll(Sort sort) { throw nope(); }
        @Override public Page<MerchantDocument> findAll(Pageable pageable) { throw nope(); }
        @Override public <S extends MerchantDocument> Optional<S> findOne(Example<S> example) { throw nope(); }
        @Override public <S extends MerchantDocument> List<S> findAll(Example<S> example) { throw nope(); }
        @Override public <S extends MerchantDocument> List<S> findAll(Example<S> example, Sort sort) { throw nope(); }
        @Override public <S extends MerchantDocument> Page<S> findAll(Example<S> example, Pageable pageable) { throw nope(); }
        @Override public <S extends MerchantDocument> long count(Example<S> example) { throw nope(); }
        @Override public <S extends MerchantDocument> boolean exists(Example<S> example) { throw nope(); }
        @Override public <S extends MerchantDocument, R> R findBy(
                Example<S> example,
                Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw nope(); }

        private static UnsupportedOperationException nope() {
            return new UnsupportedOperationException(
                    "MongoRepository method not exercised by MongoBackedMerchantRepository — fake intentionally unsupported");
        }
    }
}
