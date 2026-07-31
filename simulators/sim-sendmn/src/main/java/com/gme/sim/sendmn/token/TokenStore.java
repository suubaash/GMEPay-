package com.gme.sim.sendmn.token;

import com.gme.sim.sendmn.config.SimSendmnProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session-token issuer/validator. Tokens are opaque base64 strings valid for
 * {@code token-validity-minutes} (doc: 90). {@link #expireAll()} force-expires every
 * live token — used by tests and by POST /sim/expire-tokens to drive the adapter's
 * S104 → re-auth path.
 */
@Component
public class TokenStore {

    public enum Status { VALID, MISSING, INVALID, EXPIRED }

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final Duration validity;

    public TokenStore(SimSendmnProperties props) {
        this.validity = Duration.ofMinutes(props.getTokenValidityMinutes());
    }

    /** Issues a fresh token (base64 of a random UUID pair, opaque like the real thing). */
    public String issue() {
        String token = Base64.getEncoder().encodeToString(
                (UUID.randomUUID() + ":" + UUID.randomUUID()).getBytes());
        tokens.put(token, Instant.now().plus(validity));
        return token;
    }

    public Status validate(String token) {
        if (token == null || token.isBlank()) {
            return Status.MISSING;
        }
        Instant expiry = tokens.get(token);
        if (expiry == null) {
            return Status.INVALID;
        }
        return Instant.now().isBefore(expiry) ? Status.VALID : Status.EXPIRED;
    }

    /** Force-expires all issued tokens (kept in the map so they answer S104, not S102). */
    public void expireAll() {
        Instant past = Instant.now().minusSeconds(1);
        tokens.replaceAll((t, exp) -> past);
    }
}
