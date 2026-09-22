package com.chitthi.document.web;

import com.chitthi.document.model.Document;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentNotFoundException;
import com.chitthi.document.service.DocumentUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    // TODO(FR14): replace with the authenticated principal once Google
    // OAuth sign-in lands; every document is scoped to this ownerId until then.
    private static final String DEFAULT_OWNER_ID = "demo-user";

    private final DocumentUploadService uploadService;
    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;

    public DocumentController(DocumentUploadService uploadService,
                               DocumentRepository documentRepository,
                               PageRepository pageRepository) {
        this.uploadService = uploadService;
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
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
}
