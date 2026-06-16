package com.gme.pay.trace;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.ProducerListener;

/**
 * Auto-configuration for the GMEPay+ transparency tracer. When {@code gmepay.trace.enabled=true}
 * it installs, with zero per-service code:
 * <ul>
 *   <li>a {@link TraceReporter} bean (also usable by schedulers/async jobs);</li>
 *   <li>outbound HTTP capture on autoconfigured {@code RestClient}/{@code RestTemplate};</li>
 *   <li>inbound HTTP capture via a servlet filter (MVC) or a {@link TraceReactiveFilter} (WebFlux);</li>
 *   <li>Kafka produce/consume capture when spring-kafka is on the classpath.</li>
 * </ul>
 * Discovered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * so any service that depends on lib-errors gets it for free. Off by default.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "gmepay.trace", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TraceProperties.class)
public class TraceAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TraceReporter traceReporter(TraceProperties props, Environment env) {
        String name = props.getServiceName();
        if (name == null || name.isBlank()) {
            name = env.getProperty("spring.application.name", "unknown");
        }
        return new TraceReporter(name, props.getIngestUrl(), props.getQueueCapacity());
    }

    // ---- outbound HTTP -----------------------------------------------------

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
    public RestClientCustomizer traceRestClientCustomizer(TraceReporter reporter) {
        TraceClientHttpInterceptor interceptor = new TraceClientHttpInterceptor(reporter);
        return builder -> builder.requestInterceptor(interceptor);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    public RestTemplateCustomizer traceRestTemplateCustomizer(TraceReporter reporter) {
        TraceClientHttpInterceptor interceptor = new TraceClientHttpInterceptor(reporter);
        return template -> template.getInterceptors().add(interceptor);
    }

    // ---- inbound HTTP: servlet --------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletInbound {
        @Bean
        public FilterRegistrationBean<TraceServletFilter> traceServletFilter(TraceReporter reporter) {
            FilterRegistrationBean<TraceServletFilter> reg =
                    new FilterRegistrationBean<>(new TraceServletFilter(reporter));
            reg.setOrder(Ordered.LOWEST_PRECEDENCE);
            reg.addUrlPatterns("/*");
            reg.setName("gmeTraceServletFilter");
            return reg;
        }
    }

    // ---- inbound HTTP: reactive (gateway) ---------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class ReactiveInbound {
        @Bean
        public TraceReactiveFilter traceReactiveFilter(TraceReporter reporter) {
            return new TraceReactiveFilter(reporter);
        }
    }

    // ---- Kafka produce/consume --------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    static class KafkaInbound {

        @Bean
        @ConditionalOnMissingBean(RecordInterceptor.class)
        public RecordInterceptor<Object, Object> traceKafkaConsumerInterceptor(TraceReporter reporter) {
            return new RecordInterceptor<>() {
                @Override
                public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                                Consumer<Object, Object> consumer) {
                    reporter.report("kafka:" + record.topic(), reporter.self(), "CONSUME",
                            "/" + record.topic(), 200, 0, "Txn Flow",
                            "partition=" + record.partition() + " offset=" + record.offset());
                    return record;
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean(ProducerListener.class)
        public ProducerListener<Object, Object> traceKafkaProducerListener(TraceReporter reporter) {
            return new ProducerListener<>() {
                @Override
                public void onSuccess(ProducerRecord<Object, Object> record, RecordMetadata recordMetadata) {
                    reporter.report(reporter.self(), "kafka:" + record.topic(), "PRODUCE",
                            "/" + record.topic(), 200, 0, "Txn Flow", null);
                }
            };
        }
    }
}
