package com.gme.pay.scheme.zeropay.adapter.model;

import java.time.LocalTime;

/**
 * Configuration for a single ZeroPay batch file type (SCH-06 §7.3).
 */
public record FileTypeConfig(
        BatchType fileType,
        FileDirection direction,
        LocalTime nominalKstTime,
        int retentionDays,
        boolean isFullSync,
        SyncFrequency frequency
) {

    public enum FileDirection { INBOUND, OUTBOUND }

    public enum SyncFrequency { DAILY, WEEKLY, ON_DEMAND }
}
