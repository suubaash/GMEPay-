package com.gme.pay.ratefx.xe;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit test for {@link XeRateClient}: verifies it correctly parses the
 * sim-rate-provider JSON shape (BigDecimal strings, not floats).
 */
class XeRateClientTest {

    private static final String SIM_JSON = """
            {
              "base": "USD",
              "asOf": "2026-06-13T10:00:00+09:00",
              "source": "SIM_XE",
              "quotes": {
                "KRW": "1380.000000",
                "MNT": "3450.000000",
                "KHR": "4100.000000",
                "VND": "25400.000000",
                "THB": "36.500000",
                "SGD": "1.350000",
                "CNY": "7.200000"
              }
            }
            """;

    @Test
    void fetchUsdRates_parsesBaseAndSource() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sim-xe");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://sim-xe/v1/rates?base=USD"))
                .andRespond(withSuccess(SIM_JSON, MediaType.APPLICATION_JSON));

        XeRateClient client = new XeRateClient(builder.build());
        XeMultiRateResponse resp = client.fetchUsdRates();

        assertThat(resp.base()).isEqualTo("USD");
        assertThat(resp.source()).isEqualTo("SIM_XE");
        server.verify();
    }

    @Test
    void fetchUsdRates_parsesAllSevenCurrencies() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sim-xe");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://sim-xe/v1/rates?base=USD"))
                .andRespond(withSuccess(SIM_JSON, MediaType.APPLICATION_JSON));

        XeRateClient client = new XeRateClient(builder.build());
        XeMultiRateResponse resp = client.fetchUsdRates();

        assertThat(resp.quotes()).containsKeys("KRW", "MNT", "KHR", "VND", "THB", "SGD", "CNY");
        // Rates must parse as BigDecimal without loss
        BigDecimal krw = new BigDecimal(resp.quotes().get("KRW"));
        assertThat(krw).isEqualByComparingTo(new BigDecimal("1380.000000"));
        server.verify();
    }
}
