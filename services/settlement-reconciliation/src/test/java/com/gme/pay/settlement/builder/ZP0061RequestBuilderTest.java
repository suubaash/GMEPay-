package com.gme.pay.settlement.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Golden-file + field-padding tests for the ZP0061/ZP0063 outbound settlement-request builder. */
class ZP0061RequestBuilderTest {

    private final ZP0061RequestBuilder builder = new ZP0061RequestBuilder("ZP0061");

    private static BuildContext.MerchantRow net(String id, long gross, long fee, long net) {
        return new BuildContext.MerchantRow(id, 2, BigDecimal.valueOf(gross), 0, BigDecimal.ZERO,
                BigDecimal.valueOf(fee), BigDecimal.valueOf(net), BigDecimal.ZERO, RoundingMode.HALF_UP, 'N');
    }

    private static BuildContext.MerchantRow gross(String id, long gross) {
        return new BuildContext.MerchantRow(id, 1, BigDecimal.valueOf(gross), 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(gross), BigDecimal.ZERO, RoundingMode.HALF_UP, 'G');
    }

    @Test
    @DisplayName("header/data/trailer are exact fixed-width; trailer total = SUM(net)")
    void goldenFile() {
        // NET merchant: gross 35000, fee 280 (35000*0.008), net 34720.  GROSS merchant: fee 0, net 50000.
        BuildContext ctx = new BuildContext("20260618", 1,
                List.of(net("M001", 35000, 280, 34720), gross("M002", 50000)));

        AbstractZeroPayFileBuilder.BuiltFile f = builder.build(ctx);
        List<String> lines = f.lines();

        assertEquals(4, lines.size(), "header + 2 data + trailer");
        assertEquals("ZP0061" + "20260618" + "001", lines.get(0));
        assertEquals(17, lines.get(0).length());

        String netLine = lines.get(1);
        assertEquals(85, netLine.length(), "DATA record fixed width");
        assertTrue(netLine.startsWith("M001      "), "merchant id AN(10) left-justified");
        assertTrue(netLine.contains("00000000035000"), "gross 35000 N(14)");
        assertTrue(netLine.contains("000000000280"), "fee 280 N(12)");
        assertTrue(netLine.contains("00000000034720"), "booked net 34720 N(14)");
        assertEquals('N', netLine.charAt(netLine.length() - 1), "settlement type");

        assertEquals('G', lines.get(2).charAt(lines.get(2).length() - 1));

        // TRAILER = EOF + count(10) + SUM(net)(16); 34720 + 50000 = 84720
        assertEquals("EOF" + "0000000002" + "0000000000084720", lines.get(3));
        assertEquals(0, f.trailerTotal().compareTo(new BigDecimal("84720")));
        assertEquals(64, f.checksum().length(), "SHA-256 hex");
        assertEquals("ZP0061", f.fileType());
    }

    @Test
    @DisplayName("checksum is deterministic for identical content")
    void checksumDeterministic() {
        BuildContext ctx = new BuildContext("20260618", 1, List.of(net("M001", 35000, 280, 34720)));
        assertEquals(builder.build(ctx).checksum(), builder.build(ctx).checksum());
    }

    @Test
    @DisplayName("ZP0063 afternoon reuses the layout via the fileCode flag")
    void afternoonFileCode() {
        ZP0061RequestBuilder afternoon = new ZP0061RequestBuilder("ZP0063");
        AbstractZeroPayFileBuilder.BuiltFile f =
                afternoon.build(new BuildContext("20260618", 2, List.of(gross("M002", 50000))));
        assertTrue(f.lines().get(0).startsWith("ZP0063"));
        assertEquals("ZP0063", f.fileType());
    }

    @Test
    @DisplayName("a field that overflows its fixed width fails fast")
    void overflowThrows() {
        // gross_amount N(14) max is 14 nines; 10^14 overflows.
        BuildContext ctx = new BuildContext("20260618", 1,
                List.of(net("M001", 100_000_000_000_000L, 0, 100_000_000_000_000L)));
        assertThrows(IllegalStateException.class, () -> builder.build(ctx));
    }
}
