package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

/**
 * Production {@link ConfigRegistryClient}. Talks to config-registry over HTTP
 * via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.config-registry.client=rest} is set; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubConfigRegistryClient} wins so the BFF
 * still boots standalone for tests / local dev.
 *
 * <p>Endpoint mapping (config-registry/PartnerController.java):
 * <ul>
 *   <li>{@code GET    /v1/partners}                  -> {@link #listPartners()}</li>
 *   <li>{@code GET    /v1/partners/{id}}             -> {@link #getPartner(String)}</li>
 *   <li>{@code POST   /v1/partners}                  -> {@link #createPartner(PartnerCreateRequest)}</li>
 *   <li>{@code PUT    /v1/partners/{id}/rounding-mode} -> {@link #updateRoundingMode(String, String)}</li>
 * </ul>
 *
 * <p>Slice 1 collapsed the wire DTO to the canonical {@link PartnerView}
 * shape — this client deserializes that directly and adapts to the BFF's
 * deprecated {@link PartnerSummary} alias via {@link PartnerSummary#fromView}.
 * Adding a partner field is now a one-line change in {@code lib-api-contracts}.
 *
 * <p>Scheme list ({@link #listSchemes()}) has no config-registry endpoint yet, so
 * we surface an empty list — the Admin schemes view degrades gracefully. When
 * config-registry exposes {@code GET /v1/schemes}, wire it here without changing
 * the contract.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.config-registry.client", havingValue = "rest")
public class RestConfigRegistryClient implements ConfigRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RestConfigRegistryClient.class);

    private final RestClient restClient;

    @Autowired
    public RestConfigRegistryClient(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestConfigRegistryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PartnerSummary getPartner(String partnerId) {
        try {
            PartnerView view = restClient.get()
                    .uri("/v1/partners/{id}", partnerId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown partner; collapse to null below.
                    })
                    .body(PartnerView.class);
            return PartnerSummary.fromView(view);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on getPartner({}): {}", partnerId, network.getMessage());
            return null;
        }
    }

    @Override
    public List<PartnerSummary> listPartners() {
        try {
            List<PartnerView> response = restClient.get()
                    .uri("/v1/partners")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PartnerView>>() {});
            if (response == null) {
                return List.of();
            }
            return response.stream()
                    .map(PartnerSummary::fromView)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listPartners: {}", network.getMessage());
            return List.of();
        }
    }

    @Override
    public PartnerSummary createPartner(PartnerCreateRequest request) {
        // Adapt the BFF's deprecated four-field request to the canonical
        // PartnerCommand.CreateDraft surface config-registry's POST now accepts.
        // Identity-step fields ride the canonical payload as null — the legacy
        // form does not carry them.
        com.gme.pay.contracts.PartnerCommand.CreateDraft body = request.toCreateDraft();
        try {
            PartnerView view = restClient.post()
                    .uri("/v1/partners")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PartnerView.class);
            return PartnerSummary.fromView(view);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx so the Admin UI can show config-registry's validation message
            // (e.g. duplicate partnerCode, bad rounding mode). Unpack the upstream Spring error
            // envelope so the UI sees "partner '...' already exists", not the entire JSON.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    private static String extractUpstreamMessage(org.springframework.web.client.RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getStatusText();
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            JsonNode msg = node.get("message");
            if (msg != null && !msg.isNull()) {
                return msg.asText();
            }
            JsonNode err = node.get("error");
            if (err != null && !err.isNull()) {
                return err.asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body;
    }

    @Override
    public PartnerSummary updateRoundingMode(String partnerId, String mode) {
        try {
            PartnerView view = restClient.put()
                    .uri("/v1/partners/{id}/rounding-mode", partnerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("mode", mode))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown partner; collapse to null below.
                    })
                    .body(PartnerView.class);
            return PartnerSummary.fromView(view);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on updateRoundingMode({}): {}", partnerId, network.getMessage());
            return null;
        }
    }

    @Override
    public List<SchemeSummary> listSchemes() {
        // No config-registry endpoint for schemes yet. Empty list keeps the UI graceful.
        return Collections.emptyList();
    }

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------

    @Override
    public PartnerView createDraft(PartnerCommand.CreateDraft request) {
        try {
            return restClient.post()
                    .uri("/v1/partners/draft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PartnerView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx (duplicate partner_code → 409, validation → 400)
            // through to the Admin UI with the upstream message preserved.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public PartnerView patchDraftStep1(String partnerCode, PartnerCommand.UpdateStep1 request) {
        try {
            return restClient.patch()
                    .uri("/v1/partners/draft/{partnerCode}/step-1", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PartnerView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public PartnerView getDraft(String partnerCode) {
        try {
            return restClient.get()
                    .uri("/v1/partners/draft/{partnerCode}", partnerCode)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown draft; collapse to null below.
                    })
                    .body(PartnerView.class);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on getDraft({}): {}",
                    partnerCode, network.getMessage());
            return null;
        }
    }

    @Override
    public List<PartnerView> listDrafts() {
        try {
            List<PartnerView> response = restClient.get()
                    .uri("/v1/partners/drafts")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PartnerView>>() {});
            return response == null ? List.of() : response;
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listDrafts: {}", network.getMessage());
            return List.of();
        }
    }

    // -------- Slice 2 (2A.1) contact endpoints (PARTNER_SETUP_PLAN §Slice 2) --

    @Override
    public List<com.gme.pay.contracts.ContactView> patchDraftStep2(
            String partnerCode, com.gme.pay.contracts.PartnerCommand.UpdateStep2 request) {
        try {
            List<com.gme.pay.contracts.ContactView> response = restClient.patch()
                    .uri("/v1/partners/draft/{partnerCode}/step-2", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<com.gme.pay.contracts.ContactView>>() {});
            return response == null ? List.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx (validation → 400 with the offending
            // contacts[i] index, unknown draft → 404, non-ONBOARDING → 409)
            // through to the Admin UI with the upstream message preserved.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public List<com.gme.pay.contracts.ContactView> listContacts(String partnerCode) {
        try {
            List<com.gme.pay.contracts.ContactView> response = restClient.get()
                    .uri("/v1/partners/{partnerCode}/contacts", partnerCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<com.gme.pay.contracts.ContactView>>() {});
            return response == null ? List.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 404 = unknown partner code; propagate so the Admin UI can
            // distinguish "no such partner" from "partner with zero contacts"
            // (the latter is an empty 200 list from upstream).
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listContacts({}): {}",
                    partnerCode, network.getMessage());
            return List.of();
        }
    }

    // -------- Slice 4 (4A.1) bank-account endpoints (PARTNER_SETUP_PLAN §Slice 4)

    @Override
    public List<com.gme.pay.contracts.BankAccountView> patchDraftStep4(
            String partnerCode, com.gme.pay.contracts.PartnerCommand.UpdateStep4 request) {
        try {
            List<com.gme.pay.contracts.BankAccountView> response = restClient.patch()
                    .uri("/v1/partners/draft/{partnerCode}/step-4", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<com.gme.pay.contracts.BankAccountView>>() {});
            return response == null ? List.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx (validation → 400 with the offending
            // bankAccounts[i] index, unknown draft → 404, non-ONBOARDING → 409)
            // through to the Admin UI with the upstream message preserved.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public List<com.gme.pay.contracts.BankAccountView> listBankAccounts(String partnerCode) {
        try {
            List<com.gme.pay.contracts.BankAccountView> response = restClient.get()
                    .uri("/v1/partners/{partnerCode}/bank-accounts", partnerCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<com.gme.pay.contracts.BankAccountView>>() {});
            return response == null ? List.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 404 = unknown partner code; propagate so the Admin UI can
            // distinguish "no such partner" from "partner with zero accounts"
            // (the latter is an empty 200 list from upstream).
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listBankAccounts({}): {}",
                    partnerCode, network.getMessage());
            return List.of();
        }
    }

    @Override
    public com.gme.pay.contracts.BankAccountView verifyBankAccount(
            String partnerCode, Long accountId) {
        try {
            return restClient.post()
                    .uri("/v1/partners/{partnerCode}/bank-accounts/{accountId}/verify",
                            partnerCode, accountId)
                    .retrieve()
                    .body(com.gme.pay.contracts.BankAccountView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Includes upstream 502 when the verification rail is unavailable
            // (e.g. the KFTC certificate-pending placeholder) — verification is
            // an explicit operator action, so the failure must surface, never
            // collapse to null.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    // -------- Slice 4 (4B.1) settlement-config endpoints (PARTNER_SETUP_PLAN §Slice 4)

    @Override
    public com.gme.pay.contracts.SettlementConfigView patchDraftStep4Settlement(
            String partnerCode, com.gme.pay.contracts.PartnerCommand.UpdateStep4Settlement request) {
        try {
            return restClient.patch()
                    .uri("/v1/partners/draft/{partnerCode}/step-4-settlement", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(com.gme.pay.contracts.SettlementConfigView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx (validation → 400 with the offending field,
            // unknown draft → 404, non-ONBOARDING → 409) through to the Admin
            // UI with the upstream message preserved.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public com.gme.pay.contracts.SettlementConfigView getSettlementConfig(String partnerCode) {
        try {
            return restClient.get()
                    .uri("/v1/partners/{partnerCode}/settlement-config", partnerCode)
                    .retrieve()
                    .body(com.gme.pay.contracts.SettlementConfigView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 404 = unknown partner OR no settlement config yet; propagate so
            // the wizard can distinguish "nothing to rehydrate" from a
            // transport failure.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public com.gme.pay.contracts.SettlementPreview getSettlementPreview(
            String partnerCode, String txnInstant, String bankCountry) {
        try {
            org.springframework.web.util.UriComponentsBuilder uri =
                    org.springframework.web.util.UriComponentsBuilder
                            .fromUriString("/v1/partners/{partnerCode}/settlement-preview")
                            .queryParam("txnInstant", txnInstant);
            if (bankCountry != null && !bankCountry.isBlank()) {
                uri.queryParam("bankCountry", bankCountry);
            }
            return restClient.get()
                    .uri(uri.buildAndExpand(partnerCode).toUriString())
                    .retrieve()
                    .body(com.gme.pay.contracts.SettlementPreview.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 400 = malformed txnInstant / bankCountry (config-registry owns
            // the message), 404 = unknown partner or no settlement config —
            // the preview panel renders the upstream reason verbatim.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    // -------- Slice 3 (3B.1) KYB endpoints (PARTNER_SETUP_PLAN §Slice 3) ------

    @Override
    public com.gme.pay.contracts.KybView patchDraftStep3(
            String partnerCode, com.gme.pay.contracts.KybCommand.UpdateStep3 request) {
        try {
            return restClient.patch()
                    .uri("/v1/partners/draft/{partnerCode}/step-3", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(com.gme.pay.contracts.KybView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx (validation → 400 with the offending
            // uboList[i] index, unknown draft → 404, non-ONBOARDING → 409)
            // through to the Admin UI with the upstream message preserved.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public com.gme.pay.contracts.KybView getKyb(String partnerCode) {
        try {
            return restClient.get()
                    .uri("/v1/partners/{partnerCode}/kyb", partnerCode)
                    .retrieve()
                    .body(com.gme.pay.contracts.KybView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 404 = unknown partner OR no KYB row yet; propagate so the wizard
            // can distinguish "nothing to rehydrate" from a transport failure.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public com.gme.pay.contracts.KybView runKybScreening(String partnerCode) {
        try {
            return restClient.post()
                    .uri("/v1/partners/{partnerCode}/kyb/screen", partnerCode)
                    .retrieve()
                    .body(com.gme.pay.contracts.KybView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Includes upstream 502 when config-registry could not reach
            // kyb-adapter — a screening run is an explicit operator action, so
            // the failure must surface, never collapse to null.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    // -------- Slice 2 (2B.1) change-request approval endpoints (ADR-008) ------

    @Override
    public ChangeRequestPage listChangeRequests(String state, int page, int size) {
        try {
            org.springframework.web.util.UriComponentsBuilder uri =
                    org.springframework.web.util.UriComponentsBuilder
                            .fromUriString("/v1/change-requests")
                            .queryParam("page", page)
                            .queryParam("size", size);
            if (state != null && !state.isBlank()) {
                uri.queryParam("state", state);
            }
            ChangeRequestPage result = restClient.get()
                    .uri(uri.toUriString())
                    .retrieve()
                    .body(ChangeRequestPage.class);
            return result != null ? result
                    : new ChangeRequestPage(List.of(), page, size, 0L);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listChangeRequests: {}",
                    network.getMessage());
            return new ChangeRequestPage(List.of(), page, size, 0L);
        }
    }

    @Override
    public com.gme.pay.contracts.ChangeRequestView getChangeRequest(Long id) {
        try {
            return restClient.get()
                    .uri("/v1/change-requests/{id}", id)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {})
                    .body(com.gme.pay.contracts.ChangeRequestView.class);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on getChangeRequest({}): {}",
                    id, network.getMessage());
            return null;
        }
    }

    @Override
    public com.gme.pay.contracts.ChangeRequestView approveChangeRequest(
            Long id, String approvedBy) {
        try {
            return restClient.post()
                    .uri("/v1/change-requests/{id}/approve", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("approvedBy", approvedBy))
                    .retrieve()
                    .body(com.gme.pay.contracts.ChangeRequestView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public com.gme.pay.contracts.ChangeRequestView rejectChangeRequest(
            Long id, String rejectedBy, String reason) {
        try {
            return restClient.post()
                    .uri("/v1/change-requests/{id}/reject", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("rejectedBy", rejectedBy, "reason", reason))
                    .retrieve()
                    .body(com.gme.pay.contracts.ChangeRequestView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    // -------- Slice 3 (3A.1) document vault endpoints (ADR-006) ---------------

    @Override
    public com.gme.pay.contracts.DocumentView uploadDocument(
            String partnerCode, String docType, String expiryDate,
            String filename, String contentType, byte[] content) {
        // Multipart relay: the Admin UI uploads to the BFF, the BFF re-posts the
        // same parts to config-registry (browser-direct vault access is
        // forbidden per ADR-006 — this hop preserves the audit + virus-scan
        // seam). The file part carries the original filename + MIME type.
        org.springframework.core.io.ByteArrayResource fileResource =
                new org.springframework.core.io.ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
        org.springframework.http.HttpHeaders fileHeaders = new org.springframework.http.HttpHeaders();
        fileHeaders.setContentType(safeMediaType(contentType));

        org.springframework.util.MultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.http.HttpEntity<>(fileResource, fileHeaders));
        form.add("docType", docType);
        if (expiryDate != null && !expiryDate.isBlank()) {
            form.add("expiryDate", expiryDate);
        }
        try {
            return restClient.post()
                    .uri("/v1/partners/{partnerCode}/documents", partnerCode)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(com.gme.pay.contracts.DocumentView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx/5xx (bad docType → 400, unknown partner →
            // 404, non-ONBOARDING → 409, vault down → 502) with the upstream
            // message preserved for the Admin UI.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public List<com.gme.pay.contracts.DocumentView> listDocuments(String partnerCode) {
        try {
            List<com.gme.pay.contracts.DocumentView> response = restClient.get()
                    .uri("/v1/partners/{partnerCode}/documents", partnerCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<com.gme.pay.contracts.DocumentView>>() {});
            return response == null ? List.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 404 = unknown partner; propagate so the UI can distinguish it
            // from "partner with zero documents" (empty 200 list upstream).
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listDocuments({}): {}",
                    partnerCode, network.getMessage());
            return List.of();
        }
    }

    @Override
    public DocumentContent downloadDocument(String partnerCode, Long docId) {
        try {
            org.springframework.http.ResponseEntity<byte[]> entity = restClient.get()
                    .uri("/v1/partners/{partnerCode}/documents/{docId}/content",
                            partnerCode, docId)
                    .retrieve()
                    .toEntity(byte[].class);
            String filename = entity.getHeaders().getContentDisposition().getFilename();
            MediaType mediaType = entity.getHeaders().getContentType();
            return new DocumentContent(
                    filename,
                    mediaType == null
                            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                            : mediaType.toString(),
                    entity.getBody() == null ? new byte[0] : entity.getBody());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    /** Parse the uploaded MIME type, falling back to octet-stream on junk input. */
    private static MediaType safeMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
