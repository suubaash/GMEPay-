package com.gme.pay.settlement.port;

import java.time.LocalDate;

/**
 * Port for the settlement prerequisite check (spec §8.2, tickets 9.1-T18/T19): a ZP0061/ZP0063
 * settlement REQUEST for a business date is only allowed once that date's payment registration
 * has completed both legs — outbound ZP0011 transmitted successfully AND inbound ZP0012 result
 * received. The registration lifecycle lives in scheme-adapter-zeropay; this service consults
 * it over HTTP and NEVER reads that service's tables.
 */
public interface RegistrationStatusPort {

    RegistrationStatus statusFor(LocalDate businessDate);

    record RegistrationStatus(boolean zp0011Succeeded, boolean zp0012Received) {

        /** The §8.2 rule: settlement may generate only when BOTH registration legs completed. */
        public boolean settlementAllowed() {
            return zp0011Succeeded && zp0012Received;
        }

        public static RegistrationStatus allowed() {
            return new RegistrationStatus(true, true);
        }
    }
}
