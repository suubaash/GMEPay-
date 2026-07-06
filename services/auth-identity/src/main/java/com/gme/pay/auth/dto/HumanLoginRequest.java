package com.gme.pay.auth.dto;

/**
 * Request body for {@code POST /v1/auth/login} (human operator password login,
 * real-auth slice). Proxied verbatim by ops-partner-bff's {@code AuthController}.
 */
public record HumanLoginRequest(String username, String password) {

    /** Never echo the password from a log line. */
    @Override
    public String toString() {
        return "HumanLoginRequest[username=" + username + ", password=REDACTED]";
    }
}
