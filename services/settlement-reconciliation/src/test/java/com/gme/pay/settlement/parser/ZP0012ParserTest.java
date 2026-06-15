package com.gme.pay.settlement.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ZP0012Parser}.
 * No Spring context, no Docker.
 */
class ZP0012ParserTest {

    private ZP0012Parser parser;

    @BeforeEach
    void setUp() {
        parser = new ZP0012Parser();
    }

    @Test
    @DisplayName("Parse ZP0012 sample: 3 records, 2 approved + 1 rejected")
    void parseSample_success() {
        List<String> lines = List.of(
                "ZP0012,20260615,001",
                "TXN-001,ZP-SCH-001,50000,0000",
                "TXN-002,ZP-SCH-002,30000,0000",
                "TXN-003,ZP-SCH-003,20000,9999",
                "EOF,3,80000"
        );

        List<ZeroPayResultRecord> records = parser.parse(lines);

        assertThat(records).hasSize(5); // header + 3 data + trailer

        List<ZeroPayResultRecord> data = records.stream()
                .filter(r -> r.recordType() == ZeroPayResultRecord.RecordType.DATA)
                .toList();
        assertThat(data).hasSize(3);

        // Approved records
        assertThat(data.get(0).txnRef()).isEqualTo("TXN-001");
        assertThat(data.get(0).schemeRef()).isEqualTo("ZP-SCH-001");
        assertThat(data.get(0).amount()).isEqualByComparingTo("50000");
        assertThat(data.get(0).resultCode()).isEqualTo("0000");
        assertThat(data.get(0).isApproved()).isTrue();

        // Rejected record
        assertThat(data.get(2).resultCode()).isEqualTo("9999");
        assertThat(data.get(2).isApproved()).isFalse();

        // FileType
        assertThat(data).allSatisfy(r -> assertThat(r.fileType()).isEqualTo(ZeroPayResultRecord.FileType.ZP0012));
        // Not settlement data
        assertThat(data).noneMatch(ZeroPayResultRecord::isSettlementData);
    }

    @Test
    @DisplayName("Trailer approved total only counts 0000 codes")
    void trailerApprovedTotalOnlyCountsApproved() {
        // TXN-001 = 50000 approved, TXN-002 = 30000 REJECTED
        // trailer total = 50000 (only approved)
        List<String> lines = List.of(
                "ZP0012,20260615,002",
                "TXN-001,ZP-SCH-001,50000,0000",
                "TXN-002,ZP-SCH-002,30000,9999",
                "EOF,2,50000"
        );
        List<ZeroPayResultRecord> records = parser.parse(lines);
        assertThat(records).hasSize(4);
    }

    @Test
    @DisplayName("Trailer total mismatch throws ZeroPayFileParseException")
    void trailerMismatch_throws() {
        List<String> lines = List.of(
                "ZP0012,20260615,001",
                "TXN-001,ZP-SCH-001,50000,0000",
                "EOF,1,99999"  // wrong total
        );
        assertThatThrownBy(() -> parser.parse(lines))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("approved total mismatch");
    }

    @Test
    @DisplayName("Insufficient fields on data line throws")
    void insufficientFields_throws() {
        List<String> lines = List.of(
                "ZP0012,20260615,001",
                "TXN-001,ZP-SCH-001",   // only 2 fields
                "EOF,1,0"
        );
        assertThatThrownBy(() -> parser.parse(lines))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("fewer than 4 fields");
    }
}
