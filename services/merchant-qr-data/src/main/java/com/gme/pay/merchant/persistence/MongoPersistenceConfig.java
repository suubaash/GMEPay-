package com.gme.pay.merchant.persistence;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Conditional MongoDB wiring for the merchant/QR mirror (17.7-G01, ADR-003).
 *
 * <p>Activates ONLY when {@code spring.data.mongodb.uri} is set (e.g. via the
 * {@code SPRING_DATA_MONGODB_URI} environment variable, or a Testcontainers
 * {@code @DynamicPropertySource} in integration tests). The Mongo Spring Boot
 * auto-configurations are permanently excluded in {@code application.properties},
 * so this class is the single switch for the whole Mongo stack: client,
 * template, Spring Data repositories, and index creation.
 *
 * <p>When the property is absent nothing here is registered and the in-memory
 * {@code MerchantRepository} remains the active lookup store, keeping unit
 * tests and local development free of any Mongo daemon requirement.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.data.mongodb.uri")
@EnableMongoRepositories(basePackageClasses = MerchantMongoRepository.class)
public class MongoPersistenceConfig {

    /** Name of the unique index on the QR code lookup key of the merchants collection. */
    public static final String QR_CODE_INDEX = "idx_merchants_qr_code";

    /** Database used when the connection URI does not name one. */
    static final String DEFAULT_DATABASE = "merchant";

    @Bean(destroyMethod = "close")
    MongoClient merchantMongoClient(@Value("${spring.data.mongodb.uri}") String uri) {
        return MongoClients.create(uri);
    }

    @Bean
    MongoTemplate mongoTemplate(MongoClient merchantMongoClient,
                                @Value("${spring.data.mongodb.uri}") String uri) {
        String database = new ConnectionString(uri).getDatabase();
        if (database == null || database.isBlank()) {
            database = DEFAULT_DATABASE;
        }
        return new MongoTemplate(merchantMongoClient, database);
    }

    /**
     * Ensures the unique index on {@code qrCode} — the lookup key for
     * {@code GET /v1/merchants/{qr}} — exists at startup. Idempotent:
     * {@code ensureIndex} is a no-op when the index is already present.
     */
    @Bean
    ApplicationRunner merchantQrIndexInitializer(MongoTemplate mongoTemplate) {
        return args -> mongoTemplate.indexOps(MerchantDocument.class)
                .ensureIndex(new Index()
                        .on("qrCode", Sort.Direction.ASC)
                        .unique()
                        .named(QR_CODE_INDEX));
    }
}
