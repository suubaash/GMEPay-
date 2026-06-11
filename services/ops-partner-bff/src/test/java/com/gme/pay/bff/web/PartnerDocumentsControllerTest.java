package com.gme.pay.bff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Slice 3 (3A.1) MockMvc test for the BFF's document pass-throughs on
 * {@link AdminPartnerDocumentsController} — multipart
 * {@code POST .../documents}, {@code GET .../documents} and the
 * {@code GET .../documents/{docId}/content} download relay. Uses the real
 * {@link StubConfigRegistryClient} (the BFF's default wiring when
 * {@code gmepay.config-registry.client} is not {@code rest}) so the
 * upload → list → download round trip is exercised end-to-end, mirroring
 * {@link PartnerContactsControllerTest}.
 */
class PartnerDocumentsControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(new AdminPartnerDocumentsController(configRegistry))
                // Jackson for the DocumentView JSON + a byte[] converter for the
                // download relay (standalone setup registers ONLY what we list).
                .setMessageConverters(converter,
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .build();
    }

    /** Seed a draft directly through the stub so the document endpoints have a target. */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, null, null, null, null, null, null, null, null));
    }

    private static MockMultipartFile pdf(String filename, String body) {
        return new MockMultipartFile("file", filename, "application/pdf",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("POST /v1/admin/partners/{code}/documents uploads and returns 201 with metadata")
    void upload_returnsCreatedWithMetadata() throws Exception {
        createDraft("doc_partner_001");

        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_001")
                        .file(pdf("license-2026.pdf", "LICENSE-PDF-BYTES"))
                        .param("docType", "LICENSE")
                        .param("expiryDate", "2027-06-30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.docType").value("LICENSE"))
                .andExpect(jsonPath("$.filename").value("license-2026.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sha256").value(sha256("LICENSE-PDF-BYTES")))
                .andExpect(jsonPath("$.expiryDate").value("2027-06-30"))
                .andExpect(jsonPath("$.verifiedBy").value((String) null));
    }

    @Test
    @DisplayName("re-uploading the same docType bumps version; list shows one current row per type")
    void reUpload_bumpsVersion_listShowsCurrentSet() throws Exception {
        createDraft("doc_partner_002");

        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_002")
                        .file(pdf("lic-v1.pdf", "license v1"))
                        .param("docType", "LICENSE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_002")
                        .file(pdf("lic-v2.pdf", "license v2"))
                        .param("docType", "LICENSE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_002")
                        .file(pdf("aoa.pdf", "articles"))
                        .param("docType", "AOA"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/v1/admin/partners/{code}/documents", "doc_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.docType=='LICENSE')].version").value(2))
                .andExpect(jsonPath("$[?(@.docType=='LICENSE')].filename").value("lic-v2.pdf"));
    }

    @Test
    @DisplayName("GET .../documents/{docId}/content relays bytes, content type and filename")
    void download_relaysBytesAndHeaders() throws Exception {
        createDraft("doc_partner_003");

        MvcResult uploaded = mvc.perform(
                        multipart("/v1/admin/partners/{code}/documents", "doc_partner_003")
                                .file(pdf("cbddq.pdf", "WOLFSBERG-CBDDQ-BYTES"))
                                .param("docType", "CBDDQ"))
                .andExpect(status().isCreated())
                .andReturn();
        long docId = new ObjectMapper()
                .readTree(uploaded.getResponse().getContentAsByteArray())
                .get("id").asLong();

        MvcResult download = mvc.perform(
                        get("/v1/admin/partners/{code}/documents/{docId}/content",
                                "doc_partner_003", docId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray())
                .isEqualTo("WOLFSBERG-CBDDQ-BYTES".getBytes(StandardCharsets.UTF_8));
        assertThat(download.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("cbddq.pdf");
    }

    @Test
    @DisplayName("GET .../documents returns [] for a draft with no documents yet")
    void list_emptyForFreshDraft() throws Exception {
        createDraft("doc_partner_004");

        mvc.perform(get("/v1/admin/partners/{code}/documents", "doc_partner_004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("upload with a docType outside the roster returns 400")
    void upload_badDocTypeReturns400() throws Exception {
        createDraft("doc_partner_005");

        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_005")
                        .file(pdf("p.pdf", "x"))
                        .param("docType", "PASSPORT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("upload with a malformed expiryDate returns 400")
    void upload_badExpiryDateReturns400() throws Exception {
        createDraft("doc_partner_006");

        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_006")
                        .file(pdf("l.pdf", "x"))
                        .param("docType", "LICENSE")
                        .param("expiryDate", "30/06/2027"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("upload with an empty file part returns 400")
    void upload_emptyFileReturns400() throws Exception {
        createDraft("doc_partner_007");

        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "doc_partner_007")
                        .file(new MockMultipartFile("file", "empty.pdf", "application/pdf",
                                new byte[0]))
                        .param("docType", "LICENSE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown partner returns 404 on upload, list and download")
    void unknownPartner_returns404Everywhere() throws Exception {
        mvc.perform(multipart("/v1/admin/partners/{code}/documents", "ghost_partner")
                        .file(pdf("l.pdf", "x"))
                        .param("docType", "LICENSE"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/documents", "ghost_partner"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/admin/partners/{code}/documents/{docId}/content",
                        "ghost_partner", 600_000L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a docId belonging to another partner is 404, not leaked")
    void crossPartnerDocId_returns404() throws Exception {
        createDraft("doc_partner_008");
        createDraft("doc_partner_009");

        MvcResult uploaded = mvc.perform(
                        multipart("/v1/admin/partners/{code}/documents", "doc_partner_008")
                                .file(pdf("fs.pdf", "financials"))
                                .param("docType", "FINANCIALS"))
                .andExpect(status().isCreated())
                .andReturn();
        long docId = new ObjectMapper()
                .readTree(uploaded.getResponse().getContentAsByteArray())
                .get("id").asLong();

        mvc.perform(get("/v1/admin/partners/{code}/documents/{docId}/content",
                        "doc_partner_009", docId))
                .andExpect(status().isNotFound());
    }
}
