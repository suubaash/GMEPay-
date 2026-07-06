package com.gme.pay.settlement.client;

import com.gme.pay.settlement.port.RegistrationStatusPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * In-process {@link RegistrationStatusPort} default for dev/test, matching the module's other
 * fixture adapters: when {@code settlement.clients.scheme-adapter-zeropay.enabled} is off there
 * is no adapter to consult, so the prerequisite gate is PERMISSIVE (settlement generation keeps
 * working exactly as before this gate existed). Production/compose enable the REST client, where
 * the gate is real and fail-closed.
 */
@Component
@ConditionalOnMissingBean(RestRegistrationStatusClient.class)
public class PermissiveRegistrationStatusAdapter implements RegistrationStatusPort {

    private static final Logger log = LoggerFactory.getLogger(PermissiveRegistrationStatusAdapter.class);

    @Override
    public RegistrationStatus statusFor(LocalDate businessDate) {
        log.debug("registration gate permissive for {} (scheme-adapter-zeropay client disabled)", businessDate);
        return RegistrationStatus.allowed();
    }
}
