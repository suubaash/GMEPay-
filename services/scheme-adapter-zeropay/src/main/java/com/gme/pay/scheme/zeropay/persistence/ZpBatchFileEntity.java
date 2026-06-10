package com.gme.pay.scheme.zeropay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Registry row for one ZeroPay ZP00xx batch file — generated for transmission
 * (OUTBOUND) or fetched from the scheme SFTP inbound directory (INBOUND).
 *
 * <p>Maps {@code zp_batch_files} (V001 migration). Records the SHA-256 checksum of
 * the file bytes plus the SFTP transmission/pickup window timestamps so duplicate
 * generation and out-of-window transfers can be detected.</p>
 *
 * <p>KRW control sums are {@link BigDecimal} with scale 0 (NUMERIC(20,0)) per
 * {@code docs/MONEY_CONVENTION.md} — never double/float/minor units.</p>
 */
@Entity
@Table(name = "zp_batch_files")
public class ZpBatchFileEntity {

    /** Lifecycle status values (application-enforced; see V001 column comment). */
    public static final String STATUS_GENERATED   = "GENERATED";
    public static final String STATUS_TRANSMITTED = "TRANSMITTED";
    public static final String STATUS_RECEIVED    = "RECEIVED";
    public static final String STATUS_PARSED      = "PARSED";
    public static final String STATUS_PROCESSED   = "PROCESSED";
    public static final String STATUS_PARSE_ERROR = "PARSE_ERROR";

    public static final String DIRECTION_OUTBOUND = "OUTBOUND";
    public static final String DIRECTION_INBOUND  = "INBOUND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_type", nullable = false, length = 8)
    private String fileType;

    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "file_name", nullable = false, length = 128)
    private String fileName;

    @Column(name = "sha256_checksum", nullable = false, length = 64)
    private String sha256Checksum;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "control_sum_krw", nullable = false, precision = 20, scale = 0)
    private BigDecimal controlSumKrw;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "window_opens_at", nullable = false)
    private Instant windowOpensAt;

    @Column(name = "window_closes_at", nullable = false)
    private Instant windowClosesAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA only. */
    protected ZpBatchFileEntity() {
    }

    private ZpBatchFileEntity(String fileType, String direction, LocalDate businessDate,
                              int sequenceNo, String fileName, String sha256Checksum,
                              long fileSizeBytes, int recordCount, BigDecimal controlSumKrw,
                              String status, Instant windowOpensAt, Instant windowClosesAt) {
        this.fileType = fileType;
        this.direction = direction;
        this.businessDate = businessDate;
        this.sequenceNo = sequenceNo;
        this.fileName = fileName;
        this.sha256Checksum = sha256Checksum;
        this.fileSizeBytes = fileSizeBytes;
        this.recordCount = recordCount;
        this.controlSumKrw = controlSumKrw;
        this.status = status;
        this.windowOpensAt = windowOpensAt;
        this.windowClosesAt = windowClosesAt;
    }

    /** Registers a freshly generated outbound file (status {@code GENERATED}). */
    public static ZpBatchFileEntity outbound(String fileType, LocalDate businessDate,
                                             int sequenceNo, String fileName,
                                             String sha256Checksum, long fileSizeBytes,
                                             int recordCount, BigDecimal controlSumKrw,
                                             Instant windowOpensAt, Instant windowClosesAt) {
        return new ZpBatchFileEntity(fileType, DIRECTION_OUTBOUND, businessDate, sequenceNo,
                fileName, sha256Checksum, fileSizeBytes, recordCount, controlSumKrw,
                STATUS_GENERATED, windowOpensAt, windowClosesAt);
    }

    /** Registers a fetched inbound file (status {@code RECEIVED}). */
    public static ZpBatchFileEntity inbound(String fileType, LocalDate businessDate,
                                            int sequenceNo, String fileName,
                                            String sha256Checksum, long fileSizeBytes,
                                            int recordCount, BigDecimal controlSumKrw,
                                            Instant windowOpensAt, Instant windowClosesAt,
                                            Instant receivedAt) {
        ZpBatchFileEntity entity = new ZpBatchFileEntity(fileType, DIRECTION_INBOUND,
                businessDate, sequenceNo, fileName, sha256Checksum, fileSizeBytes,
                recordCount, controlSumKrw, STATUS_RECEIVED, windowOpensAt, windowClosesAt);
        entity.receivedAt = receivedAt;
        return entity;
    }

    // -- lifecycle transitions (application-enforced) ------------------------

    public void markTransmitted(Instant sentAt) {
        this.status = STATUS_TRANSMITTED;
        this.sentAt = sentAt;
    }

    public void markParsed() {
        this.status = STATUS_PARSED;
    }

    public void markProcessed() {
        this.status = STATUS_PROCESSED;
    }

    public void markParseError() {
        this.status = STATUS_PARSE_ERROR;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // -- accessors ------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getFileType() {
        return fileType;
    }

    public String getDirection() {
        return direction;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSha256Checksum() {
        return sha256Checksum;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public BigDecimal getControlSumKrw() {
        return controlSumKrw;
    }

    public String getStatus() {
        return status;
    }

    public Instant getWindowOpensAt() {
        return windowOpensAt;
    }

    public Instant getWindowClosesAt() {
        return windowClosesAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
