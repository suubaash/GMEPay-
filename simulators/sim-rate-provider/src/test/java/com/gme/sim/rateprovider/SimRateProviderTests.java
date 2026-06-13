package com.gme.sim.rateprovider;

import com.gme.sim.rateprovider.rates.SingleRateResponse;
import com.gme.sim.rateprovider.rates.MultiRateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimRateProviderTests {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void singleRate_usdKrw_returnsParsableBigDecimal() {
        ResponseEntity<SingleRateResponse> resp =
                rest.getForEntity(base() + "/v1/rates?base=USD&quote=KRW",
                        SingleRateResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SingleRateResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.base()).isEqualTo("USD");
        assertThat(body.quote()).isEqualTo("KRW");
        assertThat(body.source()).isEqualTo("SIM_XE");
        // Must parse as BigDecimal (not double/float)
        BigDecimal rate = new BigDecimal(body.rate());
        assertThat(rate).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void multiRate_usdBase_containsAllSeededCurrencies() {
        ResponseEntity<MultiRateResponse> resp =
                rest.getForEntity(base() + "/v1/rates?base=USD", MultiRateResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MultiRateResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.quotes()).containsKeys("KRW", "MNT", "KHR", "VND", "THB", "SGD", "CNY");
        body.quotes().values().forEach(v -> {
            BigDecimal rate = new BigDecimal(v);
            assertThat(rate).isGreaterThan(BigDecimal.ZERO);
        });
    }

    @Test
    void pairsEndpoint_nonEmpty() {
        ResponseEntity<List> resp =
                rest.getForEntity(base() + "/v1/rates/pairs", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody()).contains("USD/KRW");
    }

    @Test
    void unsupportedBase_returns400() {
        ResponseEntity<String> resp =
                rest.getForEntity(base() + "/v1/rates?base=XYZ&quote=KRW", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
