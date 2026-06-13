package com.gme.pay.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.config.IssuedCredentialBundleLogMaskingFilter;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Slice 8 Lane B — MockMvc tests for the BFF credential, IP-allowlist and mTLS
 * pass-through endpoints plus log-masking filter verification.
 */
class PartnerCredentialControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerCredentialController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private void createDraft(String code) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "Corp", null, null, "KR", null, null, null, null));
    }

    // -----------------------------------------------------------------------
    // IP allowlist
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PATCH step-8/ip-allowlist saves and returns the set")
    void patchIpAllowlist_savesAndReturns() throws Exception {
        createDraft("cred_partner_001");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/ip-allowlist",
                        "cred_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ipAllowlist":[
                                  {"cidr":"203.0.113.0/24","label":"Office","environment":"SANDBOX"},
                                  {"cidr":"198.51.100.0/24","environment":"PRODUCTION"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cidr").value("203.0.113.0/24"))
                .andExpect(jsonPath("$[0].environment").value("SANDBOX"))
                .andExpect(jsonPath("$[1].cidr").value("198.51.100.0/24"));
    }

    @Test
    @DisplayName("GET ip-allowlist rehydrates the saved set")
    void getIpAllowlist_returnsSaved() throws Exception {
        createDraft("cred_partner_002");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/ip-allowlist",
                        "cred_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ipAllowlist":[{"cidr":"10.0.0.0/8","environment":"SANDBOX"}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/ip-allowlist", "cred_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cidr").value("10.0.0.0/8"));
    }

    // -----------------------------------------------------------------------
    // mTLS cert
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PATCH step-8/mtls-cert saves and returns a cert view")
    void patchMtlsCert_savesAndReturns() throws Exception {
        createDraft("cred_partner_003");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/mtls-cert",
                        "cred_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environment":"SANDBOX","certPem":"-----BEGIN CERTIFICATE-----\\nMIIBxxx\\n-----END CERTIFICATE-----"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("SANDBOX"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.fingerprintSha256").isNotEmpty());
    }

    @Test
    @DisplayName("GET mtls-cert returns certs for both environments")
    void getMtlsCerts_returnsSaved() throws Exception {
        createDraft("cred_partner_004");
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-8/mtls-cert",
                        "cred_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environment":"SANDBOX","certPem":"-----BEGIN CERTIFICATE-----\\nMIIBsandbox\\n-----END CERTIFICATE-----"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/partners/{code}/mtls-cert", "cred_partner_004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].environment").value("SANDBOX"));
    }

    // -----------------------------------------------------------------------
    // Credential rotation + log masking
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST credentials/rotate returns one-time bundle with all three secrets")
    void rotateCredentials_returnsBundle() throws Exception {
        createDraft("cred_partner_005");
        mvc.perform(post("/v1/admin/partners/{code}/credentials/rotate",
                        "cred_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environment":"SANDBOX"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyPlaintext").isNotEmpty())
                .andExpect(jsonPath("$.hmacSecretPlaintext").isNotEmpty())
                .andExpect(jsonPath("$.webhookSecretPlaintext").isNotEmpty())
                .andExpect(jsonPath("$.apiKeyId").isNotEmpty())
                .andExpect(jsonPath("$.last4").isNotEmpty());
    }

    @Test
    @DisplayName("Log-masking filter: SUPPRESS_ATTR is set on /credentials/rotate path")
    void logMaskingFilter_credentialsRotate_suppressAttrSet() throws Exception {
        // Test the filter logic directly — verifies SEC-09 §4 without needing
        // a running Tomcat access-log pipeline.
        assertThat(IssuedCredentialBundleLogMaskingFilter.isCredentialPath(
                "/v1/admin/partners/some_partner/credentials/rotate")).isTrue();
        assertThat(IssuedCredentialBundleLogMaskingFilter.isCredentialPath(
                "/v1/admin/partners/some_partner/lifecycle/activate")).isTrue();
        assertThat(IssuedCredentialBundleLogMaskingFilter.isCredentialPath(
                "/v1/admin/partners/some_partner/credentials")).isFalse();
        assertThat(IssuedCredentialBundleLogMaskingFilter.isCredentialPath(
                "/v1/admin/partners/some_partner/lifecycle/suspend")).isFalse();
        assertThat(IssuedCredentialBundleLogMaskingFilter.isCredentialPath(null)).isFalse();
    }

    @Test
    @DisplayName("Log-masking filter: request attribute is set on POST /credentials/rotate")
    void logMaskingFilter_setsAttributeOnMatchingRequest() throws Exception {
        // Drive the filter directly on a mock request to verify the attribute contract.
        IssuedCredentialBundleLogMaskingFilter filter = new IssuedCredentialBundleLogMaskingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest(
                "POST", "/v1/admin/partners/p1/credentials/rotate");
        jakarta.servlet.http.HttpServletResponse resp =
                new org.springframework.mock.web.MockHttpServletResponse();
        boolean[] chainCalled = {false};
        filter.doFilter(req, resp, (rq, rs) -> chainCalled[0] = true);
        assertThat(chainCalled[0]).isTrue();
        assertThat(req.getAttribute(IssuedCredentialBundleLogMaskingFilter.SUPPRESS_ATTR))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Log-masking filter: attribute NOT set on non-sensitive GET path")
    void logMaskingFilter_doesNotSetAttributeOnGetPath() throws Exception {
        IssuedCredentialBundleLogMaskingFilter filter = new IssuedCredentialBundleLogMaskingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest(
                "GET", "/v1/admin/partners/p1/credentials");
        jakarta.servlet.http.HttpServletResponse resp =
                new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(req, resp, (rq, rs) -> {});
        assertThat(req.getAttribute(IssuedCredentialBundleLogMaskingFilter.SUPPRESS_ATTR))
                .isNull();
    }

    @Test
    @DisplayName("GET credentials returns empty list when none issued")
    void getCredentials_emptyWhenNoneIssued() throws Exception {
        createDraft("cred_partner_006");
        mvc.perform(get("/v1/admin/partners/{code}/credentials", "cred_partner_006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
