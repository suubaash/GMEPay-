package com.gme.pay.bff.web.dto;

/**
 * Wire shape for {@code POST /v1/auth/login}. Forwarded verbatim by
 * {@link com.gme.pay.bff.web.AuthController} to auth-identity's human login
 * endpoint, which verifies the salted PBKDF2 credential and mints a real
 * HS256 JWT. There is no demo password.
 */
public record LoginRequest(String username, String password) {

    /** Never echo the password from a log line. */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=REDACTED]";
    }
}
