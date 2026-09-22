package com.chitthi.document;

import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.document.web.DocumentView;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end for Day 3-4's slice of the pipeline: a multi-page PDF upload
 * lands as one Page row per page, in order, each pointing at a stored
 * image. Queueing OCR work for those pages is a separate, later concern.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentUploadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static MinIOContainer minio = new MinIOContainer(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-09-13T20-26-02Z")
                    .asCompatibleSubstituteFor("minio/minio"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("minio.endpoint", minio::getS3URL);
        registry.add("minio.access-key", minio::getUserName);
        registry.add("minio.secret-key", minio::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void uploadTwelvePagePdf_createsOnePageRowPerPage() throws IOException {
        byte[] pdf = buildBlankPdf(12);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Nanaji's 1987 letter");
        body.add("language", "hi");
        body.add("tags", "family");
        body.add("year", "1987");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<DocumentUploadResponse> uploadResponse = restTemplate.postForEntity(
                "/api/documents", request, DocumentUploadResponse.class);

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var documentId = uploadResponse.getBody().id();

        ResponseEntity<DocumentView> getResponse = restTemplate.getForEntity(
                "/api/documents/" + documentId, DocumentView.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        DocumentView view = getResponse.getBody();
        assertThat(view.pages()).hasSize(12);
        for (int i = 0; i < 12; i++) {
            assertThat(view.pages().get(i).pageNo()).isEqualTo(i + 1);
            assertThat(view.pages().get(i).status()).isEqualTo("PENDING");
        }
        assertThat(view.tags()).containsExactly("family");
        assertThat(view.year()).isEqualTo(1987);
    }

    private byte[] buildBlankPdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
