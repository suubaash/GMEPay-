package com.gme.pay.auth.config;

import com.gme.pay.auth.domain.SecretHasher;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import com.gme.pay.auth.persistence.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 (PostgreSQL-compat) slice test for {@link DevAdminSeeder}: on a fresh
 * Flyway schema it creates the {@code admin} operator with a runtime-derived
 * PBKDF2 credential (V007 columns) and the V002-seeded HUB_ADMIN role, and it
 * is idempotent — a second run leaves the existing principal untouched.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DevAdminSeederSliceTest {

    @Autowired
    private PrincipalRepository principals;

    @Autowired
    private RoleRepository roles;

    @Test
    void seedsAdminOnce_withVerifiablePasswordAndHubAdminRole() {
        DevAdminSeeder seeder = new DevAdminSeeder(principals, roles);
        seeder.run();

        PrincipalEntity admin = principals.findByUsername(DevAdminSeeder.DEV_ADMIN_USERNAME).orElseThrow();
        assertThat(admin.getType()).isEqualTo(PrincipalEntity.Type.OPERATOR);
        assertThat(admin.getStatus()).isEqualTo(PrincipalEntity.Status.ACTIVE);
        assertThat(admin.hasPassword()).isTrue();
        assertThat(admin.getPasswordIterations()).isEqualTo(SecretHasher.CURRENT_ITERATIONS);
        assertThat(admin.getRoles()).extracting(RoleEntity::getCode).containsExactly("HUB_ADMIN");

        // The runtime-derived hash verifies against the documented dev password…
        assertThat(SecretHasher.matches(DevAdminSeeder.DEV_ADMIN_PASSWORD,
                admin.getPasswordSalt(), admin.getPasswordIterations(), admin.getPasswordHash()))
                .isTrue();
        // …and only against it.
        assertThat(SecretHasher.matches("demo",
                admin.getPasswordSalt(), admin.getPasswordIterations(), admin.getPasswordHash()))
                .isFalse();

        // Idempotent: second run neither duplicates nor rotates the credential.
        String firstHash = admin.getPasswordHash();
        seeder.run();
        assertThat(principals.findAll().stream()
                .filter(p -> DevAdminSeeder.DEV_ADMIN_USERNAME.equals(p.getUsername()))).hasSize(1);
        assertThat(principals.findByUsername(DevAdminSeeder.DEV_ADMIN_USERNAME).orElseThrow()
                .getPasswordHash()).isEqualTo(firstHash);
    }
}
