package com.gme.pay.settlement.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ZP0062Parser}.
 * Parses the sample fixture and verifies all records are extracted correctly.
 * No Spring context, no Docker.
 */
class ZP0062ParserTest {

    private ZP0062Parser parser;

    @BeforeEach
    void setUp() {
        parser = new ZP0062Parser();
    }

    @Test
    @DisplayName("Parse ZP0062 sample fixture: 3 data lines, correct amounts, trailer validates")
    void parseSampleFixture_success() {
        // Matches src/main/resources/fixtures/ZP0062_sample.txt
        List<String> lines = List.of(
                "ZP006220260615001",
                "MRC001          0000100000000000",
                "MRC002          0000250000000000",
                "MRC003          0000075000000000",
                "EOF0000000003000000425000000000"
        );

        List<ZeroPayResultRecord> records = parser.parse(lines);

        // 1 header + 3 data + 1 trailer = 5
        assertThat(records).hasSize(5);

        // Header
        assertThat(records.get(0).recordType()).isEqualTo(ZeroPayResultRecord.RecordType.HEADER);
        assertThat(records.get(0).fileType()).isEqualTo(ZeroPayResultRecord.FileType.ZP0062);

        // Data records
        List<ZeroPayResultRecord> dataRecords = records.stream()
                .filter(r -> r.recordType() == ZeroPayResultRecord.RecordType.DATA)
                .toList();
        assertThat(dataRecords).hasSize(3);

        assertThat(dataRecords.get(0).merchantId()).isEqualTo("MRC001");
        assertThat(dataRecords.get(0).amount()).isEqualByComparingTo("100000000000");

        assertThat(dataRecords.get(1).merchantId()).isEqualTo("MRC002");
        assertThat(dataRecords.get(1).amount()).isEqualByComparingTo("250000000000");

        assertThat(dataRecords.get(2).merchantId()).isEqualTo("MRC003");
        assertThat(dataRecords.get(2).amount()).isEqualByComparingTo("75000000000");

        // All data records are settlement data
        assertThat(dataRecords).allMatch(ZeroPayResultRecord::isSettlementData);

        // Trailer
        ZeroPayResultRecord trailer = records.get(4);
        assertThat(trailer.recordType()).isEqualTo(ZeroPayResultRecord.RecordType.TRAILER);
    }

    @Test
    @DisplayName("Parse fixture with smaller amounts matching the spec amounts used in diff tests")
    void parseFixtureSmallAmounts_success() {
        List<String> lines = List.of(
                "ZP006220260615002",
                "MRC001          0000000000034720",
                "MRC002          0000000000100000",
                "EOF0000000002000000000000134720"
        );

        List<ZeroPayResultRecord> records = parser.parse(lines);
        List<ZeroPayResultRecord> data = records.stream()
                .filter(r -> r.recordType() == ZeroPayResultRecord.RecordType.DATA)
                .toList();

        assertThat(data).hasSize(2);
        assertThat(data.get(0).merchantId()).isEqualTo("MRC001");
        assertThat(data.get(0).amount()).isEqualByComparingTo(new BigDecimal("34720"));
        assertThat(data.get(1).merchantId()).isEqualTo("MRC002");
        assertThat(data.get(1).amount()).isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("Empty file throws ZeroPayFileParseException")
    void emptyFile_throws() {
        assertThatThrownBy(() -> parser.parse(List.of()))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Missing trailer throws ZeroPayFileParseException")
    void missingTrailer_throws() {
        List<String> lines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720"
                // no EOF line
        );
        assertThatThrownBy(() -> parser.parse(lines))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("TRAILER");
    }

    @Test
    @DisplayName("Wrong header prefix throws ZeroPayFileParseException")
    void wrongHeader_throws() {
        List<String> lines = List.of(
                "ZP006420260615001",  // ZP0064 in a ZP0062 parser
                "MRC001          0000000000034720",
                "EOF0000000001000000000000034720"
        );
        assertThatThrownBy(() -> parser.parse(lines))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("ZP0062");
    }

    @Test
    @DisplayName("Trailer count mismatch throws ZeroPayFileParseException")
    void trailerCountMismatch_throws() {
        List<String> lines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000002000000000000034720"  // count=2 but only 1 data line
        );
        assertThatThrownBy(() -> parser.parse(lines))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("count mismatch");
    }

    @Test
    @DisplayName("Trailer total mismatch throws ZeroPayFileParseException")
    void trailerTotalMismatch_throws() {
        List<String> lines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000001000000000000099999"  // wrong total
        );
        assertThatThrownBy(() -> parser.parse(lines))
                .isInstanceOf(ZeroPayFileParseException.class)
                .hasMessageContaining("total amount mismatch");
    }
}
