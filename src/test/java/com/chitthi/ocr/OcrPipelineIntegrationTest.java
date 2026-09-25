package com.chitthi.ocr;

import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.ocr.model.OcrBatchStatus;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.support.DigitiseResultZips;
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
import java.time.Duration;
import java.util.List;
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
 * The Day 3-4 acceptance criterion: a 12-page PDF upload ends with 12 pages
 * of OCR text in Postgres, having gone through real chunking (two batches,
 * one 10-page and one 2-page), two real submit calls, two independent status
 * poll cycles, and two result downloads - all against a real Postgres and
 * RabbitMQ, plus LocalStack's S3 service standing in for object storage
 * (MinIO's own images are no longer freely pullable - see the class-level
 * note on {@code localstack} below), with only the Sarvam API itself stubbed
 * via WireMock.
 *
 * <p>Two things that make this test correct rather than accidentally passing:
 * <ul>
 *   <li>WireMock starts in a static initializer alongside the containers,
 *   because {@code @DynamicPropertySource} runs before {@code @BeforeAll} -
 *   starting it there would leave {@code sarvam.base-url} pointing at a port
 *   nothing is listening on yet.</li>
 *   <li>The two {@code POST /digitise} stubs are matched by a substring of
 *   the submitted ZIP's filename ({@code _1-10.zip} / {@code _11-12.zip}),
 *   not by call order - both batches publish in the same instant, so
 *   ordering would nondeterministically bind the wrong result ZIP to the
 *   wrong range. This is exactly why {@code OcrPayloadPackager} puts the page
 *   range in the filename it submits.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Production backoff starts at 5s; at that rate this test's two
        // batches x two poll cycles would take the better part of a minute.
        "chitthi.ocr.poller.sweep-interval-ms=200",
        "chitthi.ocr.poll.initial-delay=200ms",
        "chitthi.ocr.poll.max-delay=500ms",
        "chitthi.ocr.poll.jitter-ratio=0"
})
class OcrPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Stands in for MinIO: {@link com.chitthi.storage.ObjectStorageService}
     * talks to it through the same MinIO Java client, which speaks the
     * generic S3 API rather than anything MinIO-specific. Swapped in because
     * MinIO removed its Docker Hub images in September 2026 and put quay.io's
     * copy behind paid-tier auth, leaving no free image left to pull.
     */
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    // Started here, not in a @BeforeAll: @DynamicPropertySource below runs
    // before any @BeforeAll method, so sarvam.base-url must already be a
    // live, bound port by the time this class is initialized.
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
    OcrBatchRepository ocrBatchRepository;

    @Test
    void twelvePagePdf_reachesTwelvePagesOfOcrText() throws IOException {
        stubDigitiseSubmit("_1-10.zip", "job-1-10");
        stubDigitiseSubmit("_11-12.zip", "job-11-12");
        stubStatusRunningThenCompleted("job-1-10");
        stubStatusRunningThenCompleted("job-11-12");
        stubDownload("job-1-10", DigitiseResultZips.perPageJson(10, i -> "Chunk text page " + i));
        stubDownload("job-11-12", DigitiseResultZips.perPageJson(2, i -> "Second chunk page " + i));

        UUID documentId = uploadTwelvePagePdf();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);
            assertThat(pages).hasSize(12);
            assertThat(pages).allSatisfy(page -> {
                assertThat(page.getStatus()).isEqualTo(PageStatus.OCR_DONE);
                assertThat(page.getOriginalText()).isNotBlank();
                assertThat(page.getTextHash()).hasSize(64);
            });
        });

        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);
        // The off-by-one that actually matters: pages 11-12 must carry the
        // *second* chunk's text, not get skipped or mapped onto chunk 1's.
        assertThat(pages.get(10).getOriginalText()).isEqualTo("Second chunk page 1");
        assertThat(pages.get(11).getOriginalText()).isEqualTo("Second chunk page 2");

        var batches = ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId);
        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(batch -> {
            assertThat(batch.getStatus()).isEqualTo(OcrBatchStatus.COMPLETED);
            assertThat(batch.getNextPollAt()).isNull();
        });

        // Exactly one paid submit per batch - the embryo of the "0 duplicate
        // paid API calls on retry" success metric.
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/doc-ai/v1/job/digitise"))
                .withRequestBody(containing("_1-10.zip")));
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/doc-ai/v1/job/digitise"))
                .withRequestBody(containing("_11-12.zip")));
    }

    private UUID uploadTwelvePagePdf() throws IOException {
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

        ResponseEntity<DocumentUploadResponse> response = restTemplate.postForEntity(
                "/api/documents", request, DocumentUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().id();
    }

    private void stubDigitiseSubmit(String filenameSuffix, String jobId) {
        wireMockServer.stubFor(post(urlPathEqualTo("/doc-ai/v1/job/digitise"))
                .withRequestBody(containing(filenameSuffix))
                .willReturn(okJson("{\"job_id\": \"%s\"}".formatted(jobId))));
    }

    /**
     * Two calls, not one: the first "running" response, only answered
     * "completed" on the second, proves the poller actually re-schedules via
     * next_poll_at instead of happening to succeed on the first tick.
     */
    private void stubStatusRunningThenCompleted(String jobId) {
        wireMockServer.stubFor(get(urlPathEqualTo("/doc-ai/v1/job/" + jobId + "/status"))
                .inScenario(jobId + "-status")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson("{\"job_id\": \"%s\", \"status\": \"running\"}".formatted(jobId)))
                .willSetStateTo("polled-once"));
        wireMockServer.stubFor(get(urlPathEqualTo("/doc-ai/v1/job/" + jobId + "/status"))
                .inScenario(jobId + "-status")
                .whenScenarioStateIs("polled-once")
                .willReturn(okJson("{\"job_id\": \"%s\", \"status\": \"completed\"}".formatted(jobId))));
    }

    private void stubDownload(String jobId, byte[] resultZip) {
        wireMockServer.stubFor(get(urlPathEqualTo("/doc-ai/v1/job/" + jobId + "/download-url"))
                .willReturn(okJson("{\"download_url\": \"%s/results/%s.zip\"}"
                        .formatted(wireMockServer.baseUrl(), jobId))));
        wireMockServer.stubFor(get(urlPathEqualTo("/results/" + jobId + ".zip"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(resultZip)));
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
