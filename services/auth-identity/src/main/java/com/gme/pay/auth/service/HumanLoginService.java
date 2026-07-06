package com.gme.pay.auth.service;

import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.domain.SecretHasher;
import com.gme.pay.auth.dto.HumanLoginResponse;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Human operator password login (real-auth slice): verifies a username/password
 * pair against the salted PBKDF2 credential on {@code principals} (V007) and
 * mints a REAL HS256 JWT via the existing {@link JwtHelper} — no new crypto.
 *
 * <p>Claims: {@code sub} = username, plus {@code preferred_username} (same
 * value, matching what the SPAs decode from Keycloak tokens) and {@code roles}
 * (the principal's role codes from {@code principal_roles}).
 *
 * <p><strong>Timing-uniform failures:</strong> every failed path (unknown user,
 * INACTIVE principal, principal without a local password, wrong password) still
 * performs one full PBKDF2 derivation against a dummy credential before
 * rejecting, so an attacker cannot distinguish "user exists" from "user does
 * not exist" by response latency. All failures collapse to the same 401.
 */
@Service
public class HumanLoginService {

    /**
     * Dummy credential used to equalize the timing of failure paths. The salt is
     * fixed and the expected hash deliberately does NOT derive from any real
     * password, so the comparison always fails after paying full PBKDF2 cost.
     */
    private static final String DUMMY_SALT_HEX = "00112233445566778899aabbccddeeff";
    private static final String DUMMY_HASH_HEX =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private final PrincipalRepository principals;
    private final JwtHelper jwtHelper;
    private final long tokenTtlSeconds;

    public HumanLoginService(
            PrincipalRepository principals,
            JwtHelper jwtHelper,
            @Value("${gme.auth.jwt.access-token-ttl-seconds:1800}") long tokenTtlSeconds) {
        this.principals = principals;
        this.jwtHelper = jwtHelper;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * Authenticates the pair and mints a signed HS256 JWT.
     *
     * @throws ResponseStatusException 401 for every failure mode (indistinguishable).
     */
    @Transactional
    public HumanLoginResponse login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw unauthorized();
        }

        Optional<PrincipalEntity> found = principals.findByUsername(username.trim());
        if (found.isEmpty()) {
            burnAndReject(password);
        }
        PrincipalEntity principal = found.get();
        if (principal.getStatus() != PrincipalEntity.Status.ACTIVE || !principal.hasPassword()) {
            burnAndReject(password);
        }

        boolean ok = SecretHasher.matches(
                password,
                principal.getPasswordSalt(),
                principal.getPasswordIterations(),
                principal.getPasswordHash());
        if (!ok) {
            throw unauthorized();
        }

        List<String> roles = principal.getRoles().stream()
                .map(RoleEntity::getCode)
                .sorted(Comparator.naturalOrder())
                .toList();

        String token = jwtHelper.issue(
                principal.getUsername(),
                Map.of("preferred_username", principal.getUsername(), "roles", roles),
                tokenTtlSeconds);
        long expiresAt = Instant.now().getEpochSecond() + tokenTtlSeconds;

        principal.setLastLoginAt(Instant.now());
        principals.save(principal);

        return HumanLoginResponse.bearer(token, expiresAt, principal.getUsername(), roles);
    }

    /**
     * Pay the full PBKDF2 cost against the dummy credential, then reject.
     * Keeps unknown-user / non-loginable-principal latency in line with a
     * wrong-password attempt on a real principal.
     */
    private void burnAndReject(String candidate) {
        SecretHasher.matches(candidate, DUMMY_SALT_HEX, SecretHasher.CURRENT_ITERATIONS, DUMMY_HASH_HEX);
        throw unauthorized();
    }

    private static ResponseStatusException unauthorized() {
        // One message for every failure mode — never reveal which check failed.
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
}
