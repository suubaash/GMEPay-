package com.gme.pay.auth.dto;

/**
 * Request body for {@code POST /internal/auth/keys/resolve}.
 *
 * @param apiKey the public key identifier presented in {@code X-API-Key}.
 */
public record CredentialLookupRequest(String apiKey) {
}
