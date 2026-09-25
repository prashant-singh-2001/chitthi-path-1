package com.chitthi.document;

import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.document.web.DocumentView;
import com.chitthi.ocr.repository.OcrBatchRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end for Day 3-4's upload slice: a multi-page PDF upload lands as one
 * Page row per page, in order, each pointing at a stored image, plus one
 * ocr_batch row per chunk of up to 10 pages. A RabbitMQ container is required
 * here even though this test never consumes a message: RabbitMqConfig
 * declares exchanges/queues, and Spring AMQP's auto-configured RabbitAdmin
 * eagerly declares that topology against a real broker at context startup.
 *
 * <p>{@code chitthi.ocr.worker.enabled=false} keeps this test's context from
 * running the real OCR worker against the published batches - this class has
 * no WireMock stub for the Sarvam API, so a live listener here would either
 * hang retrying a connection or, worse, call the real api.sarvam.ai.
 *
 * <p>Object storage is faked with LocalStack's S3 service, not a real MinIO
 * container - MinIO pulled its images from Docker Hub in September 2026 and
 * gated quay.io's copy behind paid-tier auth, so neither registry serves a
 * free anonymous pull any more. {@link com.chitthi.storage.ObjectStorageService}
 * talks to it through the same MinIO Java client either way, since that
 * client speaks the generic S3 API rather than anything MinIO-specific.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "chitthi.ocr.worker.enabled=false")
class DocumentUploadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("minio.endpoint", () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("minio.access-key", localstack::getAccessKey);
        registry.add("minio.secret-key", localstack::getSecretKey);
        registry.add("minio.region", localstack::getRegion);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OcrBatchRepository ocrBatchRepository;

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
        assertThat(view.status()).isEqualTo("PROCESSING");
        assertThat(view.pages()).hasSize(12);
        for (int i = 0; i < 12; i++) {
            assertThat(view.pages().get(i).pageNo()).isEqualTo(i + 1);
            assertThat(view.pages().get(i).status()).isEqualTo("PENDING");
        }
        assertThat(view.tags()).containsExactly("family");
        assertThat(view.year()).isEqualTo(1987);

        // 12 pages at the default 10-page chunk size is two ocr_batch rows.
        var batches = ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId);
        assertThat(batches).extracting(b -> b.getPageRange())
                .containsExactlyInAnyOrder("1-10", "11-12");
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
