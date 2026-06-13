package com.gme.pay.registry.web;

import com.gme.pay.contracts.DocumentView;
import com.gme.pay.registry.document.PartnerDocumentService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 3 (3A.1) — partner KYB document endpoints (wizard step 3 document
 * upload area + document viewer). Pure HTTP adapter over
 * {@link PartnerDocumentService}; the bytes live in the ADR-006 vault, the
 * metadata in {@code partner_document} (V010).
 *
 * <ul>
 *   <li>{@code POST /v1/partners/{partnerCode}/documents} — multipart upload
 *       ({@code file} + {@code docType} + optional {@code expiryDate}); 201
 *       with the fresh {@link DocumentView}.</li>
 *   <li>{@code GET  /v1/partners/{partnerCode}/documents} — the current set
 *       (at most one row per doc type).</li>
 *   <li>{@code GET  /v1/partners/{partnerCode}/documents/{docId}/content} —
 *       streamed download passthrough (works for superseded ids too: version
 *       history).</li>
 * </ul>
 *
 * <p>{@code {partnerCode}} is the human-facing business code, same URL contract
 * as every other partner endpoint (the BIGINT surrogate never rides the URL).
 */
@RestController
@RequestMapping("/v1/partners/{partnerCode}/documents")
public class PartnerDocumentController {

    private final PartnerDocumentService documentService;

    public PartnerDocumentController(PartnerDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Upload one document. {@code expiryDate} is ISO-8601 ({@code yyyy-MM-dd});
     * a malformed value is a 400 before any byte is stored.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentView> upload(
            @PathVariable String partnerCode,
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType,
            @RequestParam(name = "expiryDate", required = false) String expiryDate,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "file is required and must not be empty");
        }
        LocalDate parsedExpiry = parseExpiryDate(expiryDate);
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            // Some clients omit the part filename; fall back to the doc type so
            // the vault key still gets a sensible terminal segment.
            filename = docType == null ? "document" : docType.toLowerCase(java.util.Locale.ROOT);
        }
        try {
            DocumentView view = documentService.upload(
                    partnerCode,
                    docType,
                    parsedExpiry,
                    filename,
                    file.getContentType(),
                    file.getInputStream(),
                    actor);
            return ResponseEntity.status(HttpStatus.CREATED).body(view);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read multipart upload", e);
        }
    }

    /** The CURRENT document set; empty list for a partner with no documents yet. */
    @GetMapping
    public List<DocumentView> list(@PathVariable String partnerCode) {
        return documentService.currentDocuments(partnerCode);
    }

    /**
     * Stream one stored document back (download passthrough — the browser never
     * talks to the vault, per ADR-006). Serves superseded rows too so the
     * document viewer's version history can open old versions.
     */
    @GetMapping("/{docId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String partnerCode,
                                                       @PathVariable Long docId) {
        PartnerDocumentService.DocumentDownload download =
                documentService.download(partnerCode, docId);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.meta().contentType());
        } catch (RuntimeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        // ContentDisposition handles RFC 6266 filename* encoding for non-ASCII
        // (Korean) filenames.
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.meta().filename(), java.nio.charset.StandardCharsets.UTF_8)
                .build());
        long size = download.object().size();
        if (size >= 0) {
            headers.setContentLength(size);
        }
        return new ResponseEntity<>(new InputStreamResource(download.object().content()),
                headers, HttpStatus.OK);
    }

    private static LocalDate parseExpiryDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expiryDate must be an ISO-8601 date (yyyy-MM-dd), was: " + raw);
        }
    }
}
