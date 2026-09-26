package com.chitthi.pipeline;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.support.DigitiseResultZips;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Day 8-9 acceptance criterion: a chaos test finishes with 0 duplicate
 * paid calls. Rather than killing a real worker process - which would push
 * the same shared Testcontainers CI runner into the resource pressure that
 * crashed Postgres mid-test on Day 7 (see
 * {@code TranslateTtsPipelineIntegrationTest}'s history) - this injects
 * failures Sarvam itself can produce (a transient 5xx, a persistent one) and
 * proves the retry/idempotency machinery recovers correctly:
 *
 * <ul>
 *   <li>a call that fails once recovers through the delayed retry tier and
 *       is called exactly twice - the retry reuses the same
 *       {@code stage_task} idempotency key, so a successful second attempt
 *       is not itself a duplicate of anything that "worked";</li>
 *   <li>a call that fails persistently exhausts its retry budget (FR7's 3
 *       attempts), marks the page FAILED, and settles the document at
 *       PARTIAL - then {@code POST /retry} recovers it to COMPLETE without
 *       re-paying for the page's already-successful translate call.</li>
 * </ul>
 *
 * <p>A real "kill -9 mid-call" scenario is exercised manually (see the
 * README) rather than in CI, for the same resource-pressure reason.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.poller.sweep-interval-ms=200",
        "chitthi.ocr.poll.initial-delay=200ms",
        "chitthi.ocr.poll.max-delay=500ms",
        "chitthi.ocr.poll.jitter-ratio=0",
        "chitthi.outbox.relay-interval-ms=100",
        "chitthi.retry.delays=500ms,1s"
})
class ChaosPipelineIntegrationTest {

    private static final int STUB_WAV_FRAMES = 50;

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

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PageRepository pageRepository;

    @Autowired
    DocumentRepository documentRepository;

    @Test
    void translateRecoversThroughTheRetryTierWithNoDuplicateCallOnceItSucceeds() throws IOException {
        stubDigitiseSubmit("job-1");
        stubStatusCompleted("job-1");
        stubDownload("job-1", DigitiseResultZips.perPageJson(1, i -> "Only page text"));
        stubTranslateFailsOnceThenSucceeds();
        stubTextToSpeech();

        UUID documentId = uploadOnePagePdf();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETE);
        });

        // Exactly one retry: the first call failed, the second (through the
        // delayed retry tier, same stage_task key) succeeded and was never
        // called again after that.
        wireMockServer.verify(2, postRequestedFor(urlPathEqualTo("/translate")));

        Page page = pageRepository.findByDocumentIdOrderByPageNo(documentId).get(0);
        assertThat(page.getStatus()).isEqualTo(PageStatus.INDEXED);
    }

    @Test
    void aPersistentlyFailingStageExhaustsRetriesAndTheManualRetryEndpointRecoversIt() throws IOException {
        stubDigitiseSubmit("job-2");
        stubStatusCompleted("job-2");
        stubDownload("job-2", DigitiseResultZips.perPageJson(1, i -> "Only page text"));
        stubTranslate();
        stubTextToSpeechAlwaysFails();

        UUID documentId = uploadOnePagePdf();

        // 1 initial attempt + 2 retries (chitthi.retry.delays=500ms,1s) = 3
        // total, per FR7 - then the page gives up and the document is PARTIAL.
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARTIAL);
        });
        Page failedPage = pageRepository.findByDocumentIdOrderByPageNo(documentId).get(0);
        assertThat(failedPage.getStatus()).isEqualTo(PageStatus.FAILED);

        // A newer stub registration outranks the older one for future
        // requests (WireMock's documented tie-break), so text-to-speech
        // starts succeeding from here on with no need to touch (or reset)
        // the translate stub or the request journal - the "exactly one
        // translate call, ever" assertion below stays a single true count.
        stubTextToSpeech();

        ResponseEntity<Object> retryResponse = restTemplate.postForEntity(
                "/api/documents/{id}/retry", null, Object.class, documentId);
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETE);
        });
        Page recoveredPage = pageRepository.findByDocumentIdOrderByPageNo(documentId).get(0);
        assertThat(recoveredPage.getStatus()).isEqualTo(PageStatus.INDEXED);
        // The page's one and only translate call, from before it ever
        // reached TTS - the retry endpoint never re-ran it for a page that
        // already had a translated_text, only the stage it actually fell
        // out of.
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/translate")));
    }

    private UUID uploadOnePagePdf() throws IOException {
        byte[] pdf = buildBlankPdf(1);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Chaos test letter");
        body.add("language", "hi");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<DocumentUploadResponse> response = restTemplate.postForEntity(
                "/api/documents", request, DocumentUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().id();
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
                .willReturn(okJson("{\"translated_text\": \"TRANSLATED\"}")));
    }

    /** Fails the endpoint's own retry chain (a real 5xx, not a 429) exactly once, then behaves normally. */
    private void stubTranslateFailsOnceThenSucceeds() {
        String scenario = "translate-fails-once";
        wireMockServer.stubFor(post(urlPathEqualTo("/translate"))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(post(urlPathEqualTo("/translate"))
                .inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"translated_text\": \"TRANSLATED\"}")));
    }

    private void stubTextToSpeech() {
        String base64Wav = Base64.getEncoder().encodeToString(generateWav());
        wireMockServer.stubFor(post(urlPathEqualTo("/text-to-speech"))
                .willReturn(okJson("{\"request_id\": \"req-1\", \"audios\": [\"%s\"]}".formatted(base64Wav))));
    }

    private void stubTextToSpeechAlwaysFails() {
        wireMockServer.stubFor(post(urlPathEqualTo("/text-to-speech"))
                .willReturn(aResponse().withStatus(500)));
    }

    private static byte[] generateWav() {
        AudioFormat format = new AudioFormat(22050f, 16, 1, true, false);
        byte[] data = new byte[STUB_WAV_FRAMES * format.getFrameSize()];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(data), format, STUB_WAV_FRAMES)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
