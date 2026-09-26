package com.chitthi.document.web;

import com.chitthi.audio.AudioProperties;
import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentNotFoundException;
import com.chitthi.document.service.DocumentRetryService;
import com.chitthi.document.service.DocumentUploadService;
import com.chitthi.progress.ProgressProperties;
import com.chitthi.progress.ProgressSnapshot;
import com.chitthi.progress.ProgressSnapshotService;
import com.chitthi.progress.SseEmitterRegistry;
import com.chitthi.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    // TODO(FR14): replace with the authenticated principal once Google
    // OAuth sign-in lands; every document is scoped to this ownerId until then.
    private static final String DEFAULT_OWNER_ID = "demo-user";
    private static final Set<String> VALID_AUDIO_LANGUAGES = Set.of("orig", "en");

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final ObjectStorageService storageService;
    private final AudioProperties audioProperties;
    private final SseEmitterRegistry emitterRegistry;
    private final ProgressSnapshotService snapshotService;
    private final ProgressProperties progressProperties;
    private final DocumentRetryService retryService;

    public DocumentController(DocumentUploadService uploadService,
                               DocumentRepository documentRepository,
                               PageRepository pageRepository,
                               ObjectStorageService storageService,
                               AudioProperties audioProperties,
                               SseEmitterRegistry emitterRegistry,
                               ProgressSnapshotService snapshotService,
                               ProgressProperties progressProperties,
                               DocumentRetryService retryService) {
        this.uploadService = uploadService;
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.storageService = storageService;
        this.audioProperties = audioProperties;
        this.emitterRegistry = emitterRegistry;
        this.snapshotService = snapshotService;
        this.progressProperties = progressProperties;
        this.retryService = retryService;
    }

    @PostMapping
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("title") String title,
            @RequestParam("language") String language,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String ownerId = userId != null ? userId : DEFAULT_OWNER_ID;
        Document document = uploadService.upload(ownerId, title, language, tags, year, files);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new DocumentUploadResponse(document.getId()));
    }

    @GetMapping("/{id}")
    public DocumentView get(@PathVariable UUID id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        return DocumentView.from(document, pageRepository.findByDocumentIdOrderByPageNo(id));
    }

    /**
     * {@code orig} falls back to the {@code en} track when the document's
     * source language isn't one Bulbul supports, since that language never
     * gets its own track from {@link com.chitthi.tts.TtsWorker}.
     */
    @GetMapping("/{id}/audio")
    public AudioUrlResponse audio(@PathVariable UUID id, @RequestParam String lang) {
        if (!VALID_AUDIO_LANGUAGES.contains(lang)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lang must be 'orig' or 'en'");
        }
        if (!documentRepository.existsById(id)) {
            throw new DocumentNotFoundException(id);
        }

        String key = "documents/%s/audio/%s.wav".formatted(id, lang);
        boolean fallback = false;
        if (!storageService.exists(key)) {
            if (!"orig".equals(lang)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio available for document " + id);
            }
            key = "documents/%s/audio/en.wav".formatted(id);
            fallback = true;
            if (!storageService.exists(key)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio available for document " + id);
            }
        }

        String url = storageService.presignedGetUrl(key, audioProperties.urlTtl());
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(audioProperties.urlTtl());
        return new AudioUrlResponse(url, expiresAt, fallback);
    }

    /**
     * Streams {@link ProgressSnapshot}s as {@code progress}-named SSE events:
     * one immediately on connect, then one after every stage transition until
     * the document reaches a terminal status, at which point the stream
     * completes on its own. {@link com.chitthi.progress.ProgressHeartbeat}
     * keeps the connection alive between transitions.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        ProgressSnapshot snapshot = snapshotService.load(id).orElseThrow(() -> new DocumentNotFoundException(id));

        SseEmitter emitter = emitterRegistry.register(id, progressProperties.emitterTimeout().toMillis());
        try {
            emitter.send(SseEmitter.event().name("progress").data(snapshot));
            if (DocumentStatus.valueOf(snapshot.status()).isTerminal()) {
                emitterRegistry.completeAll(id);
            }
        } catch (IOException e) {
            log.warn("Failed to send the initial progress snapshot for document {}; completing with error", id, e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** FR7's manual retry: re-queues every FAILED page. See {@link DocumentRetryService}. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<RetryResponse> retry(@PathVariable UUID id) {
        DocumentRetryService.RetryResult result = retryService.retryFailedPages(id);
        return ResponseEntity.accepted().body(new RetryResponse(result.requeued(), result.skipped()));
    }
}
