package com.chitthi.cap;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.support.DigitiseResultZips;
import com.chitthi.support.TestAuth;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR15's milestone: with a small configured cap, page 1's words spend the
 * whole budget and page 2 stops at CAPPED before ever reaching translate or
 * TTS - proving the cap actually prevents the paid calls it exists to
 * prevent, not just that a status field changes. The document still settles
 * (PARTIAL, not stuck at PROCESSING), and a second upload is rejected once
 * the budget is spent.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.poller.sweep-interval-ms=200",
        "chitthi.ocr.poll.initial-delay=200ms",
        "chitthi.ocr.poll.max-delay=500ms",
        "chitthi.ocr.poll.jitter-ratio=0",
        "chitthi.outbox.relay-interval-ms=100",
        "chitthi.security.dev-user=cap-test-user",
        "chitthi.cap.daily-words=3"
})
class WordCapIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wireMockServer.start();
    }

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
        registry.add("sarvam.base-url", wireMockServer::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PageRepository pageRepository;

    @Autowired
    DocumentRepository documentRepository;

    @Test
    void laterPagesStopAtCappedOnceTheDailyBudgetIsSpent() {
        stubDigitiseSubmit("job-cap");
        stubStatusCompleted("job-cap");
        // Page 1 is exactly 3 words (spends the whole 3-word cap); page 2 is
        // any nonzero word count, so it can never fit what's left.
        stubDownload("job-cap", DigitiseResultZips.perPageJson(2, i -> i == 1 ? "one two three" : "four five six"));
        stubTranslate();
        stubTextToSpeech();

        UUID documentId = uploadTwoPagePdf();

        awaitDocumentStatus(documentId, DocumentStatus.PARTIAL);

        Page page1 = findPage(documentId, 1);
        Page page2 = findPage(documentId, 2);
        assertThat(page1.getStatus()).isEqualTo(PageStatus.INDEXED);
        assertThat(page2.getStatus()).isEqualTo(PageStatus.CAPPED);
        assertThat(page2.getOriginalText()).isEqualTo("four five six");

        // The cap's whole point: no paid call for the page it stopped.
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/translate")).withRequestBody(containing("four five six")));
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/translate")).withRequestBody(containing("one two three")));

        // A second upload is rejected outright - the budget is fully spent.
        ResponseEntity<String> secondUpload = attemptUpload();
        assertThat(secondUpload.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(secondUpload.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    private Page findPage(UUID documentId, int pageNo) {
        return pageRepository.findByDocumentIdOrderByPageNo(documentId).stream()
                .filter(p -> p.getPageNo() == pageNo)
                .findFirst()
                .orElseThrow();
    }

    private void awaitDocumentStatus(UUID documentId, DocumentStatus status) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertThat(document.getStatus()).isEqualTo(status);
        });
    }

    private UUID uploadTwoPagePdf() {
        ResponseEntity<DocumentUploadResponse> response = doUpload(DocumentUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().id();
    }

    private ResponseEntity<String> attemptUpload() {
        return doUpload(String.class);
    }

    private <T> ResponseEntity<T> doUpload(Class<T> responseType) {
        byte[] pdf = buildBlankPdf(2);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Word cap test letter");
        body.add("language", "hi");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        TestAuth.addCsrf(headers, restTemplate);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        return restTemplate.postForEntity("/api/documents", request, responseType);
    }

    private void stubDigitiseSubmit(String jobId) {
        wireMockServer.stubFor(post(urlPathEqualTo("/doc-ai/v1/job/digitise"))
                .willReturn(okJson("{\"job_id\": \"%s\"}".formatted(jobId))));
    }

    private void stubStatusCompleted(String jobId) {
        wireMockServer.stubFor(get(urlPathEqualTo("/doc-ai/v1/job/" + jobId + "/status"))
                .willReturn(okJson("{\"job_id\": \"%s\", \"status\": \"completed\"}".formatted(jobId))));
    }

    private void stubDownload(String jobId, byte[] resultZip) {
        wireMockServer.stubFor(get(urlPathEqualTo("/doc-ai/v1/job/" + jobId + "/download-url"))
                .willReturn(okJson("{\"download_url\": \"%s/results/%s.zip\"}"
                        .formatted(wireMockServer.baseUrl(), jobId))));
        wireMockServer.stubFor(get(urlPathEqualTo("/results/" + jobId + ".zip"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(resultZip)));
    }

    private void stubTranslate() {
        wireMockServer.stubFor(post(urlPathEqualTo("/translate"))
                .willReturn(okJson("{\"translated_text\": \"translated\"}")));
    }

    private void stubTextToSpeech() {
        String base64Wav = Base64.getEncoder().encodeToString(generateWav());
        wireMockServer.stubFor(post(urlPathEqualTo("/text-to-speech"))
                .willReturn(okJson("{\"request_id\": \"req-1\", \"audios\": [\"%s\"]}".formatted(base64Wav))));
    }

    private static byte[] generateWav() {
        AudioFormat format = new AudioFormat(22050f, 16, 1, true, false);
        int frames = 10;
        byte[] data = new byte[frames * format.getFrameSize()];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(data), format, frames)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
