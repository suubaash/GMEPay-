package com.gme.pay.auth.persistence;

import com.gme.pay.auth.domain.NonceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice integration test for the JPA-backed {@link NonceStore}.
 *
 * Boots only the JPA layer (and Flyway, which is wired by Spring Boot's
 * test slice). {@code @AutoConfigureTestDatabase(replace = NONE)} keeps the
 * application's configured datasource — i.e. the in-memory H2 (PostgreSQL
 * compatibility mode) declared in {@code application.yml} — so V001 runs
 * against the same schema definition used at runtime.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaNonceStore.class)
class NoncePersistenceIT {

    @Autowired
    private JpaNonceStore store;

    @Autowired
    private NonceRepository repository;

    @Test
    void recordingNonce_persistsRow() {
        boolean first = store.checkAndSet("42", "nonce-A", Duration.ofSeconds(600));

        assertThat(first).as("first time the nonce is seen").isTrue();
        assertThat(repository.existsById("nonce-A"))
                .as("row should be persisted in used_nonces").isTrue();
    }

    @Test
    void checkingSameNonceAgain_returnsSeen() {
        Duration ttl = Duration.ofSeconds(600);

        boolean first  = store.checkAndSet("42", "nonce-B", ttl);
        boolean replay = store.checkAndSet("42", "nonce-B", ttl);

        assertThat(first).as("first call inserts").isTrue();
        assertThat(replay).as("second call detects replay").isFalse();
    }
}
