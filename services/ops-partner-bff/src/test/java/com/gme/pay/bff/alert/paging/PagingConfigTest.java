package com.gme.pay.bff.alert.paging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PagingConfig} adapter selection: {@link LogPagingAdapter} fallback when no webhook
 * URL is configured; {@link WebhookPagingAdapter} when {@code gmepay.ops.paging.webhook-url}
 * is set.
 */
class PagingConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PagingConfig.class);

    @Test
    void logAdapterIsFallbackWithoutUrl() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PagingPort.class);
            assertThat(ctx.getBean(PagingPort.class)).isInstanceOf(LogPagingAdapter.class);
        });
    }

    @Test
    void webhookAdapterWhenUrlConfigured() {
        runner.withPropertyValues("gmepay.ops.paging.webhook-url=https://oncall.example/hook")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PagingPort.class);
                    assertThat(ctx.getBean(PagingPort.class)).isInstanceOf(WebhookPagingAdapter.class);
                });
    }
}
