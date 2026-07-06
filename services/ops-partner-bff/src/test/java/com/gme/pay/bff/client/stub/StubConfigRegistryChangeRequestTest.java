package com.gme.pay.bff.client.stub;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.bff.client.ConfigRegistryClient;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the change-request reads on {@link StubConfigRegistryClient}.
 *
 * <p>Config-registry owns the 4-eyes change-request queue; the stub has no such
 * workflow. The reads must DEGRADE (empty page / null) rather than fall through to
 * the interface's {@code UnsupportedOperationException} defaults — otherwise the
 * Admin UI change-request queue 500s whenever the BFF runs on the stub (the default
 * {@code matchIfMissing=true} profile). See the "list reads degrade to empty, writes
 * stay loud" convention on {@link ConfigRegistryClient}.
 */
class StubConfigRegistryChangeRequestTest {

    private final StubConfigRegistryClient stub = new StubConfigRegistryClient();

    @Test
    void listChangeRequests_degradesToEmptyPage_ratherThanThrowing() {
        ConfigRegistryClient.ChangeRequestPage page = stub.listChangeRequests("PROPOSED", 0, 20);

        assertThat(page).isNotNull();
        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isZero();
    }

    @Test
    void getChangeRequest_returnsNull_soControllerYields404() {
        assertThat(stub.getChangeRequest(123L)).isNull();
    }
}
