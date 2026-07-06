package com.gme.pay.auth.config;

import com.gme.pay.auth.domain.SecretHasher;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import com.gme.pay.auth.persistence.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Seeds the dev operator principal {@code admin} (password {@code gmepay-dev-admin})
 * with the HUB_ADMIN role, create-if-absent, at startup.
 *
 * <p>Why a runner and not Flyway: the password is stored as a salted
 * PBKDF2-HMAC-SHA256 hash (V007 columns, same convention as api_keys) and PBKDF2
 * cannot be computed in migration SQL. The hash is derived AT RUNTIME through the
 * existing {@link SecretHasher} ({@link SecretHasher#CURRENT_ITERATIONS} = 210k),
 * so no plaintext or precomputed digest is baked into the schema history.
 *
 * <p>Gated on {@code gmepay.auth.seed-dev-admin.enabled} (default {@code true}
 * for dev; production sets {@code GMEPAY_AUTH_SEED_DEV_ADMIN_ENABLED=false}).
 * Idempotent: if a principal named {@code admin} already exists — whatever its
 * state — the seeder leaves it completely untouched.
 */
@Component
@ConditionalOnProperty(name = "gmepay.auth.seed-dev-admin.enabled",
        havingValue = "true", matchIfMissing = true)
public class DevAdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeeder.class);

    /** Dev-only credential. NOT a secret — documented in the repo for local login. */
    static final String DEV_ADMIN_USERNAME = "admin";
    static final String DEV_ADMIN_PASSWORD = "gmepay-dev-admin";
    static final String DEV_ADMIN_ROLE = "HUB_ADMIN";

    private final PrincipalRepository principals;
    private final RoleRepository roles;

    public DevAdminSeeder(PrincipalRepository principals, RoleRepository roles) {
        this.principals = principals;
        this.roles = roles;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (principals.findByUsername(DEV_ADMIN_USERNAME).isPresent()) {
            return; // create-if-absent: never touch an existing principal
        }

        PrincipalEntity admin = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, DEV_ADMIN_USERNAME,
                "Dev Hub Administrator", null, Instant.now());

        String salt = SecretHasher.newSaltHex();
        admin.setPasswordCredential(
                SecretHasher.hashHex(DEV_ADMIN_PASSWORD, salt, SecretHasher.CURRENT_ITERATIONS),
                salt,
                SecretHasher.CURRENT_ITERATIONS);

        // Grant the V002-seeded HUB_ADMIN role via principal_roles (the direct
        // join RbacResolutionService also honours alongside user_roles).
        RoleEntity hubAdmin = roles.findByCode(DEV_ADMIN_ROLE).orElseThrow(() ->
                new IllegalStateException("Role " + DEV_ADMIN_ROLE
                        + " missing — V002 seed did not run?"));
        admin.addRole(hubAdmin);

        principals.save(admin);
        log.info("Seeded dev operator principal '{}' with role {} "
                + "(disable via gmepay.auth.seed-dev-admin.enabled=false)",
                DEV_ADMIN_USERNAME, DEV_ADMIN_ROLE);
    }
}
