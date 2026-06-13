package com.gme.sim.wallet;

import com.gme.sim.wallet.config.WalletProperties;
import com.gme.sim.wallet.model.MpmPreview;
import com.gme.sim.wallet.model.PartnerProfile;
import com.gme.sim.wallet.model.Receipt;
import com.gme.sim.wallet.service.RateClient;
import com.gme.sim.wallet.service.SchemeClient;
import com.gme.sim.wallet.service.SimDownException;
import com.gme.sim.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock SchemeClient schemeClient;
    @Mock RateClient   rateClient;

    WalletService walletService;

    @BeforeEach
    void setUp() {
        // Build WalletProperties with test defaults via the @Autowired ctor
        WalletProperties props = new WalletProperties(
                "GMEREMIT",
                "http://localhost:9102",
                "http://localhost:9101",
                "500",
                "0.02"
        );
        walletService = new WalletService(props, schemeClient, rateClient);
    }

    // ------------------------------------------------------------------
    // Test 1: GMEREMIT pay = payAmountKrw + 500, no FX
    // ------------------------------------------------------------------
    @Test
    void gmeremit_pay_addsServiceFeeNoFx() {
        when(schemeClient.authorize(eq("GMEREMIT"), eq("mpm"), any(), any()))
                .thenReturn("AUTH-001");
        when(schemeClient.commit("AUTH-001")).thenReturn("TXN-001");

        Receipt r = walletService.pay(PartnerProfile.GMEREMIT, "mpm",
                "QR-PAYLOAD-123", new BigDecimal("10000"));

        assertThat(r.fxApplied()).isFalse();
        assertThat(r.chargeCurrency()).isEqualTo("KRW");
        // chargeAmount = 10000 + 500 = 10500
        assertThat(new BigDecimal(r.chargeAmount())).isEqualByComparingTo("10500");
        assertThat(r.serviceFeeKrw()).isEqualTo("500");
        assertThat(r.fxRate()).isNull();
        assertThat(r.schemeTxnRef()).isEqualTo("TXN-001");
        verifyNoInteractions(rateClient);
    }

    // ------------------------------------------------------------------
    // Test 2: SENDMN pay applies 2% margin + shows MNT charge
    // ------------------------------------------------------------------
    @Test
    void sendmn_pay_applies2pctMarginAndMntCharge() {
        // midRate KRW→MNT = 3.5 (1 KRW = 3.5 MNT, illustrative)
        when(rateClient.getMidRate("KRW", "MNT")).thenReturn(new BigDecimal("3.5"));
        when(schemeClient.authorize(eq("SENDMN"), eq("mpm"), any(), any()))
                .thenReturn("AUTH-002");
        when(schemeClient.commit("AUTH-002")).thenReturn("TXN-002");

        Receipt r = walletService.pay(PartnerProfile.SENDMN, "mpm",
                "QR-PAYLOAD-MN", new BigDecimal("10000"));

        assertThat(r.fxApplied()).isTrue();
        assertThat(r.chargeCurrency()).isEqualTo("MNT");

        // mntCharge = 10000 * 3.5 * 1.02 = 35700
        assertThat(new BigDecimal(r.chargeAmount())).isEqualByComparingTo("35700");
        // serviceFee is still 500 KRW
        assertThat(r.serviceFeeKrw()).isEqualTo("500");
        assertThat(r.fxRate()).isNotNull();
        // effectiveRate = 35700 / 10000 = 3.57
        assertThat(new BigDecimal(r.fxRate())).isEqualByComparingTo("3.570000");
    }

    // ------------------------------------------------------------------
    // Test 3: SENDMN pay — rate sim down yields SimDownException
    // ------------------------------------------------------------------
    @Test
    void sendmn_rateSimDown_throwsSimDownException() {
        when(rateClient.getMidRate("KRW", "MNT"))
                .thenThrow(new SimDownException("rate sim is not reachable"));

        assertThatThrownBy(() ->
                walletService.pay(PartnerProfile.SENDMN, "mpm", "QR", new BigDecimal("5000"))
        ).isInstanceOf(SimDownException.class)
         .hasMessageContaining("rate sim is not reachable");
    }

    // ------------------------------------------------------------------
    // Test 4: MPM scan static QR — decodes preview with null amount
    // ------------------------------------------------------------------
    @Test
    void mpmScan_staticQr_returnsPreviewWithNullAmount() {
        when(schemeClient.decodeQr("STATIC-QR-PAYLOAD"))
                .thenReturn(new MpmPreview("CU Convenience", "static", null, "KRW"));

        MpmPreview preview = walletService.scanMpm(PartnerProfile.GMEREMIT, "STATIC-QR-PAYLOAD");

        assertThat(preview.mode()).isEqualTo("static");
        assertThat(preview.merchantName()).isEqualTo("CU Convenience");
        assertThat(preview.amount()).isNull();
        assertThat(preview.currency()).isEqualTo("KRW");
    }

    // ------------------------------------------------------------------
    // Test 5: MPM scan dynamic QR — decodes preview with amount set
    // ------------------------------------------------------------------
    @Test
    void mpmScan_dynamicQr_returnsPreviewWithAmount() {
        when(schemeClient.decodeQr("DYNAMIC-QR-PAYLOAD"))
                .thenReturn(new MpmPreview("GS25", "dynamic", "15000", "KRW"));

        MpmPreview preview = walletService.scanMpm(PartnerProfile.GMEREMIT, "DYNAMIC-QR-PAYLOAD");

        assertThat(preview.mode()).isEqualTo("dynamic");
        assertThat(preview.amount()).isEqualTo("15000");
    }

    // ------------------------------------------------------------------
    // Test 6: CPM generate — returns token from scheme sim
    // ------------------------------------------------------------------
    @Test
    void cpmGenerate_returnsTokenFromSchemeSim() {
        when(schemeClient.generateCpmToken(eq("CUST-001"), any(), eq("KRW")))
                .thenReturn("CPM-TOKEN-XYZ");

        String token = walletService.generateCpmToken(PartnerProfile.GMEREMIT,
                "CUST-001", new BigDecimal("5000"), "KRW");

        assertThat(token).isEqualTo("CPM-TOKEN-XYZ");
    }
}
