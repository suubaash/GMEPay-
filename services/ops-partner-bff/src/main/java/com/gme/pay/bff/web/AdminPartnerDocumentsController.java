package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.DocumentView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 3 (3A.1) — Admin UI pass-throughs for the partner document vault
 * (wizard step-3 upload area + document viewer). Pure relays to
 * config-registry via {@link ConfigRegistryClient}; the BFF holds no document
 * state. Browser-direct vault access is forbidden per ADR-006 — this hop is
 * what preserves the audit + virus-scan seam.
 *
 * <ul>
 *   <li>{@code POST /v1/admin/partners/{partnerCode}/documents} — multipart
 *       upload ({@code file} + {@code docType} + optional {@code expiryDate});
 *       201 with the fresh {@link DocumentView}. Upstream 400/404/409/502 pass
 *       through with messages preserved.</li>
 *   <li>{@code GET /v1/admin/partners/{partnerCode}/documents} — current set
 *       (at most one row per doc type).</li>
 *   <li>{@code GET /v1/admin/partners/{partnerCode}/documents/{docId}/content}
 *       — download relay; superseded ids stay downloadable (version history).</li>
 * </ul>
 *
 * <p>Separate controller (rather than more methods on
 * {@code AdminDashboardController}) because the multipart surface brings its
 * own concerns — file size limits, content-type echoing — and because the
 * document endpoints share the {@code /v1/admin/partners/{partnerCode}} prefix
 * with future Slice-3 KYB endpoints without sharing any orchestration.
 */
@RestController
@RequestMapping("/v1/admin/partners/{partnerCode}/documents")
public class AdminPartnerDocumentsController {

    private final ConfigRegistryClient configRegistry;

    public AdminPartnerDocumentsController(ConfigRegistryClient configRegistry) {
        this.configRegistry = configRegistry;
    }

    /** Upload one KYB document onto a partner (multipart relay upstream). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentView> upload(
            @PathVariable String partnerCode,
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType,
            @RequestParam(name = "expiryDate", required = false) String expiryDate) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "file is required and must not be empty");
        }
        if (docType == null || docType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docType is required");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read multipart upload", e);
        }
        DocumentView created = configRegistry.uploadDocument(
                partnerCode,
                docType,
                expiryDate,
                file.getOriginalFilename(),
                file.getContentType(),
                content);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** The CURRENT document set; empty list for a partner with no documents yet. */
    @GetMapping
    public List<DocumentView> list(@PathVariable String partnerCode) {
        return configRegistry.listDocuments(partnerCode);
    }

    /** Download relay — bytes + original filename + MIME type from upstream. */
    @GetMapping("/{docId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String partnerCode,
                                          @PathVariable Long docId) {
        ConfigRegistryClient.DocumentContent download =
                configRegistry.downloadDocument(partnerCode, docId);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.contentType());
        } catch (RuntimeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        if (download.filename() != null && !download.filename().isBlank()) {
            // RFC 6266 filename* handles non-ASCII (Korean) filenames.
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8)
                    .build());
        }
        return new ResponseEntity<>(download.content(), headers, HttpStatus.OK);
    }
}
