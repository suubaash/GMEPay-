package com.gme.pay.qr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** QR Service — EMVCo QR parse, CRC-16 validation, CPM token generation (WBS 5.3, 5.4). */
@SpringBootApplication
public class QrServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QrServiceApplication.class, args);
    }
}
