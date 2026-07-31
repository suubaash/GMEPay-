package com.gme.pay.http;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The reactive twin of {@link HttpClientTimeoutAutoConfiguration}: puts a connect and a
 * <b>response</b> timeout on every {@link WebClient} built from the injected
 * {@code WebClient.Builder} bean (gap <b>T3-11</b> defect 1).
 *
 * <h2>Why a second auto-configuration and not one more line in the first</h2>
 *
 * <p>{@link HttpClientTimeoutAutoConfiguration} registers a {@code RestClientCustomizer}. That type
 * has nothing to do with {@code WebClient}: Boot applies it to the {@code RestClient.Builder} bean and
 * to nothing else. So the entire reactive stack — which in this platform is api-gateway, the component
 * every external request passes through — sat <b>outside the fleet-wide timeout floor altogether</b>,
 * and worse, outside the guard that was written to catch exactly this class of defect
 * ({@code OutboundHttpTimeoutWiringGuardTest} scanned for {@code RestClient.builder()} and could not
 * see a {@code WebClient} at all). The property resolved, the floor was documented as fleet-wide, and
 * three hops on the authenticated edge had no bound of any kind.
 *
 * <p>That is the same failure shape as the original defect, one layer up: <b>a mechanism whose reach
 * was assumed rather than measured.</b> Hence a distinct auto-configuration, registered in the same
 * imports file, reusing the same {@link HttpClientTimeoutProperties} so there is one set of numbers for
 * the fleet rather than two that drift.
 *
 * <h2>responseTimeout, not readTimeout — and the distinction matters</h2>
 *
 * <p>Reactor Netty has no "read timeout" in the blocking sense; it has
 * {@link HttpClient#responseTimeout(Duration)}, the maximum time between the request being sent and the
 * response <em>status line</em> arriving. That is precisely the bound this gap needs: the failure being
 * closed is a peer that completes the TCP handshake and then never answers. A
 * {@code ReadTimeoutHandler} on the channel would instead fire on any idle period, including a
 * legitimately slow-streaming body, which is a different (and here, wrong) contract.
 *
 * <p>The connect timeout goes on the channel option rather than the client, because that is where
 * Netty reads it. Both halves are needed for the same reason as in the blocking case: the response
 * timeout does nothing for a black-holed address, where the wait is the kernel's SYN-retry budget.
 *
 * <h2>Interaction with a per-call {@code .timeout(..)} operator</h2>
 *
 * <p>Some callers already wrap their {@code Mono} in a reactive {@code .timeout(Duration)}. That is not
 * a substitute for this and this is not a substitute for that. The operator bounds the <em>subscriber's
 * wait</em> and cancels; it is per-call, easy to forget, and invisible in configuration. This bounds the
 * <em>connection</em>, applies to every call whether or not anyone remembered, and is the thing that
 * releases the socket. Where both are present the tighter one fires first, which is the intended
 * outcome — the operator is a per-call budget nested inside a transport floor.
 *
 * <h2>Ordering</h2>
 *
 * <p>{@link Ordered#LOWEST_PRECEDENCE}, so the connector this installs wins over any earlier
 * customizer that installs one of its own — the same reasoning, and the same caveat, as the blocking
 * twin: a competing customizer that does <em>not</em> declare an order also defaults to
 * {@code LOWEST_PRECEDENCE}, and a tie is then broken by bean-registration order rather than by
 * anything explicit. {@code CompetingRequestFactoryCustomizerTest} documents that hazard and
 * {@code OutboundHttpTimeoutWiringGuardTest} fails on any new instance of it.
 */
@AutoConfiguration
@ConditionalOnClass({WebClient.class, HttpClient.class, ChannelOption.class})
@ConditionalOnProperty(prefix = "gmepay.http.client", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HttpClientTimeoutProperties.class)
public class WebClientTimeoutAutoConfiguration {

    @Bean
    public WebClientCustomizer gmepayWebClientTimeoutCustomizer(
            HttpClientTimeoutProperties properties) {
        return new TimeoutWebClientCustomizer(properties);
    }

    /**
     * Implements {@link Ordered} on the instance rather than relying on {@code @Order} on the factory
     * method, for the same reason as the blocking twin: Boot consumes these through
     * {@code ObjectProvider.orderedStream()}, and an instance that declares its own order is honoured
     * by every ordering path, including ones that never see the factory method's annotations.
     */
    static final class TimeoutWebClientCustomizer implements WebClientCustomizer, Ordered {

        private final HttpClientTimeoutProperties properties;

        TimeoutWebClientCustomizer(HttpClientTimeoutProperties properties) {
            this.properties = properties;
        }

        @Override
        public void customize(WebClient.Builder builder) {
            builder.clientConnector(new ReactorClientHttpConnector(httpClient(
                    properties.getConnectTimeout(), properties.getReadTimeout())));
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /**
     * A Reactor Netty client bounded on both halves. Public so a consuming service's own test can drive
     * the <em>shipped</em> transport against a real unresponsive socket, rather than asserting that a
     * property was read — which is the assertion that let the first version of this gap's fix pass while
     * reaching half the fleet.
     *
     * @param connectTimeout  time allowed to establish the TCP connection
     * @param responseTimeout time allowed between the request being sent and the response arriving —
     *                        the bound that turns "hangs forever" into a definite, catchable failure
     */
    public static HttpClient httpClient(Duration connectTimeout, Duration responseTimeout) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);
    }
}
