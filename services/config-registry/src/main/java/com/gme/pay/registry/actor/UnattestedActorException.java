package com.gme.pay.registry.actor;

import com.gme.pay.errors.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when {@code gmepay.audit.actor.require-attestation=true} and a write arrived whose
 * caller could not be attested (no valid {@code X-Gme-Internal}).
 *
 * <p>401 rather than 403: the request failed to establish <i>who</i> is calling, which is an
 * authentication outcome, not an authorisation one. The message deliberately does not say
 * which header is missing — a probe should not learn the shape of the internal-auth contract
 * from an error body.
 *
 * <p>The flag is off by default; see {@link AuditActorResolver} for why (the ops BFF does not
 * yet present the internal token on these endpoints, and that client is owned elsewhere).
 * Turning it on is the step that converts "nothing is falsely attributed" into "nothing
 * unattributed is written at all".
 */
@ResponseStatus(value = HttpStatus.UNAUTHORIZED, reason = "caller identity could not be verified")
public class UnattestedActorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnattestedActorException() {
        super("caller identity could not be verified; refusing to record an unattributed write");
    }

    /** The platform's canonical error code for this condition. */
    public ErrorCode errorCode() {
        return ErrorCode.UNAUTHORIZED;
    }
}
