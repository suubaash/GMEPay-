package com.gme.pay.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification &amp; Webhook microservice (WBS 8.6).
 * Handles signed webhook dispatch, exponential-backoff retry, DLQ promotion,
 * and per-partner webhook configuration management.
 */
@SpringBootApplication
public class NotificationWebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationWebhookApplication.class, args);
    }
}
