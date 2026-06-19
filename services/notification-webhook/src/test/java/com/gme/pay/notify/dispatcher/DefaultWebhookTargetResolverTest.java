package com.gme.pay.notify.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DefaultWebhookTargetResolver}'s partnerId extraction — in particular that it
 * reads {@code partnerId} from the canonical outbox envelope ({@code payload.partnerId}) as well as
 * a flat top-level field, so a payment.approved event drained via the outbox actually resolves a
 * partner endpoint.
 */
class DefaultWebhookTargetResolverTest {

    private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
    private final DefaultWebhookTargetResolver resolver =
            new DefaultWebhookTargetResolver(endpoints, "LIVE", "dev-secret");

    private WebhookDeliveryEntity row(String payload) {
        WebhookDeliveryEntity r = mock(WebhookDeliveryEntity.class);
        when(r.getPayload()).thenReturn(payload);
        lenient().when(r.getWebhookId()).thenReturn("TX-1");
        return r;
    }

    private void stubActiveEndpoint(long partnerId) {
        WebhookEndpointEntity ep = mock(WebhookEndpointEntity.class);
        when(ep.getWebhookUrl()).thenReturn("https://partner.example/hook");
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(partnerId, "LIVE"))
                .thenReturn(List.of(ep));
    }

    @Test
    void resolvesPartnerIdFromNestedOutboxEnvelope() {
        stubActiveEndpoint(700L);
        Optional<ResolvedTarget> target = resolver.resolve(row(
                "{\"eventType\":\"payment.approved\",\"aggregateId\":\"TX-1\","
                        + "\"payload\":{\"txnRef\":\"TX-1\",\"partnerId\":700,\"toStatus\":\"APPROVED\"}}"));
        assertThat(target).isPresent();
        assertThat(target.get().url()).isEqualTo("https://partner.example/hook");
        assertThat(target.get().secret()).isEqualTo("dev-secret");
    }

    @Test
    void resolvesFlatTopLevelPartnerId() {
        stubActiveEndpoint(700L);
        assertThat(resolver.resolve(row("{\"partnerId\":700}"))).isPresent();
    }

    @Test
    void emptyWhenNoPartnerIdAnywhere() {
        assertThat(resolver.resolve(row(
                "{\"eventType\":\"payment.approved\",\"payload\":{\"toStatus\":\"APPROVED\"}}")))
                .isEmpty();
    }
}
