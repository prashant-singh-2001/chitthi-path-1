package com.chitthi.security;

import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.document.web.DocumentView;
import com.chitthi.search.SearchResponse;
import com.chitthi.support.TestAuth;
import com.chitthi.usage.web.UsageSummaryView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR14's acceptance test: one user never sees another user's document,
 * across every document-scoped endpoint. Every ownership check happens
 * before any pipeline-status check, so this needs no WireMock stub at all -
 * the document can sit at PENDING the whole test and every 404 assertion
 * still holds.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.worker.enabled=false",
        "chitthi.ocr.poller.enabled=false",
        "chitthi.translate.worker.enabled=false",
        "chitthi.tts.worker.enabled=false",
        "chitthi.assemble.worker.enabled=false",
        "chitthi.security.dev-user=default-user"
})
class DocumentOwnershipIntegrationTest {

    private static final String OWNER_A = "owner-a";
    private static final String OWNER_B = "owner-b";

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

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void anotherUserNeverSeesTheFirstUsersDocument() {
        UUID documentId = uploadAsOwner(OWNER_A);

        assertNotFound(getAs("/api/documents/{id}", OWNER_B, documentId));
        assertNotFound(getAs("/api/documents/{id}/audio?lang=en", OWNER_B, documentId));
        assertNotFound(getAs("/api/documents/{id}/events", OWNER_B, documentId));
        assertNotFound(postAs("/api/documents/{id}/retry", OWNER_B, documentId));
        assertNotFound(putAs("/api/documents/{id}/pages/1/text", OWNER_B, Map.of("text", "hi"), documentId));
        assertNotFound(getAs("/api/documents/{id}/usage", OWNER_B, documentId));

        // Owner A can still reach their own document throughout.
        ResponseEntity<DocumentView> ownResponse = restTemplate.exchange(
                "/api/documents/{id}", HttpMethod.GET, withUser(OWNER_A), DocumentView.class, documentId);
        assertThat(ownResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherUsersDocumentNeverAppearsInSearchOrUsage() {
        UUID documentId = uploadAsOwner(OWNER_A);

        ResponseEntity<SearchResponse> searchResponse = restTemplate.exchange(
                "/api/search?q=letter", HttpMethod.GET, withUser(OWNER_B), SearchResponse.class);
        assertThat(searchResponse.getBody().hits()).noneMatch(hit -> hit.documentId().equals(documentId));

        ResponseEntity<UsageSummaryView> usageResponse = restTemplate.exchange(
                "/api/usage", HttpMethod.GET, withUser(OWNER_B), UsageSummaryView.class);
        assertThat(usageResponse.getBody().entries()).noneMatch(entry -> entry.documentId().equals(documentId));
    }

    private void assertNotFound(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> getAs(String url, String owner, UUID documentId) {
        return restTemplate.exchange(url, HttpMethod.GET, withUser(owner), String.class, documentId);
    }

    private ResponseEntity<String> postAs(String url, String owner, UUID documentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", owner);
        TestAuth.addCsrf(headers, restTemplate);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class, documentId);
    }

    private ResponseEntity<String> putAs(String url, String owner, Object body, UUID documentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", owner);
        headers.setContentType(MediaType.APPLICATION_JSON);
        TestAuth.addCsrf(headers, restTemplate);
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class, documentId);
    }

    private HttpEntity<Void> withUser(String owner) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", owner);
        return new HttpEntity<>(headers);
    }

    private UUID uploadAsOwner(String owner) {
        byte[] pdf = buildBlankPdf(1);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Owner-scoped test letter");
        body.add("language", "hi");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-User-Id", owner);
        TestAuth.addCsrf(headers, restTemplate);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<DocumentUploadResponse> response = restTemplate.postForEntity(
                "/api/documents", request, DocumentUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().id();
    }

    private byte[] buildBlankPdf(int pageCount) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
