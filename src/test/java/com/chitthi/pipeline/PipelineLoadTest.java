package com.chitthi.pipeline;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.support.DigitiseResultZips;
import com.chitthi.support.TestAuth;
import com.chitthi.support.TestPdfs;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Day 12: 20 documents uploaded in parallel, against WireMock (never real
 * Sarvam - this measures our own pipeline's queueing and rate-limiter
 * behaviour under the documented 10 requests/min Vision limit, not Sarvam's
 * own latency) and the production poll backoff and rate-limit config, not
 * the compressed intervals every other integration test uses - overriding
 * either would defeat the exercise.
 *
 * <p>20 one-page documents, not 20 ten-page ones: {@code OcrBatchPlanner}
 * makes one Digitise submit per 10-page chunk, so both shapes produce
 * exactly the same 20 Vision submits this test exists to stress, and a
 * one-page document does a tenth of the translate/TTS/assemble work - the
 * concession the Day 7 CI incident (see {@code EditFlowIntegrationTest}'s
 * Javadoc) demands for a test this size on a shared runner.
 *
 * <p>Each upload must get its own Sarvam job id -
 * {@code uq_ocr_batch_sarvam_job} is a real unique index, not just a test
 * convention - so the digitise-submit stub uses WireMock response
 * templating to mint a fresh UUID per request. Nothing downstream ever
 * checks that a status/download response's own {@code job_id} matches the
 * one requested ({@link com.chitthi.sarvam.dto.JobStatusResponse} and
 * {@link com.chitthi.sarvam.dto.DownloadUrlResponse} are read positionally
 * from the URL, not cross-checked), so those two stubs match any job id and
 * serve one shared, static response each.
 *
 * <p>Excluded from the default build and CI's normal runs (see the surefire
 * profiles in {@code pom.xml}) - run deliberately with {@code mvn test
 * -Pload}, or via the {@code load-test.yml} GitHub Actions workflow's manual
 * {@code workflow_dispatch}.
 */
@Tag("load")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.security.dev-user=load-test-user",
        // Day 12's own switch, documented in the README: a single-machine
        // load test drives every request from one address, and the per-IP
        // limit would otherwise reject most of these 20 concurrent uploads.
        "chitthi.ratelimit.per-ip-requests-per-minute=0"
        // Deliberately no chitthi.ocr.poll.* / poller.sweep-interval-ms /
        // outbox.relay-interval-ms overrides, unlike every other pipeline
        // integration test - production timing is exactly what's being
        // measured and tuned here.
})
class PipelineLoadTest {

    private static final int DOCUMENT_COUNT = 20;
    // 20 submits against a 10/min limiter is a floor of ~114s of pure
    // rate-limiter wait (SarvamResilience's 6s-per-permit refresh), plus
    // translate/TTS/assemble and the shared runner's own variance.
    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(8);
    // Deliberately looser than a performance SLO would be, and distinct from
    // (shorter than) COMPLETION_TIMEOUT above: this is a stall/regression
    // detector - it catches a stuck pipeline, not a missed target - since
    // end-to-end time here is dominated by the rate limiter and the shared
    // runner's speed varies run to run.
    private static final long P95_SANITY_CEILING_MS = Duration.ofMinutes(6).toMillis();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final WireMockServer wireMockServer = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort().templatingEnabled(true).globalTemplating(true));

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

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    DocumentRepository documentRepository;

    @Test
    void twentyDocumentsInParallelUnderTheRealVisionRateLimit() throws Exception {
        stubDigitiseSubmit();
        stubStatusCompleted();
        stubDownload(DigitiseResultZips.perPageJson(1, i -> "Load test page text"));
        stubTranslate();
        stubTextToSpeech();

        ExecutorService pool = Executors.newFixedThreadPool(DOCUMENT_COUNT);
        List<Callable<UUID>> uploads = new ArrayList<>();
        for (int i = 0; i < DOCUMENT_COUNT; i++) {
            uploads.add(this::uploadOnePagePdf);
        }

        List<UUID> documentIds;
        try {
            List<Future<UUID>> futures = pool.invokeAll(uploads);
            documentIds = new ArrayList<>(futures.size());
            for (Future<UUID> future : futures) {
                documentIds.add(future.get());
            }
        } finally {
            pool.shutdown();
        }
        assertThat(documentIds).hasSize(DOCUMENT_COUNT);

        await().atMost(COMPLETION_TIMEOUT).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            for (UUID documentId : documentIds) {
                Document document = documentRepository.findById(documentId).orElseThrow();
                assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETE);
            }
        });

        // Day 8-9's "no duplicate paid calls" guarantee, finally exercised
        // under real concurrency rather than injected failures.
        wireMockServer.verify(DOCUMENT_COUNT, postRequestedFor(urlPathEqualTo("/doc-ai/v1/job/digitise")));

        List<Document> documentsInArrivalOrder = new ArrayList<>(DOCUMENT_COUNT);
        for (UUID documentId : documentIds) {
            documentsInArrivalOrder.add(documentRepository.findById(documentId).orElseThrow());
        }
        documentsInArrivalOrder.sort(java.util.Comparator.comparing(Document::getCreatedAt));

        // Durations in the order the documents were *created*, printed below
        // next to the sorted list: the requirements' risk table promises
        // "FIFO fairness across users", and a rate-limited OCR message goes
        // back to a retry tier while a later one may take the next permit -
        // arrival order against completion order is the cheapest way to see
        // whether that promise holds. Observation only, no assertion.
        List<Long> durationsInArrivalOrderMs = new ArrayList<>(DOCUMENT_COUNT);
        for (Document document : documentsInArrivalOrder) {
            durationsInArrivalOrderMs.add(Duration.between(document.getCreatedAt(), document.getCompletedAt()).toMillis());
        }
        List<Long> durationsMs = new ArrayList<>(durationsInArrivalOrderMs);
        durationsMs.sort(Long::compareTo);

        // n=20 makes this the second-slowest document, not a robust
        // percentile - reported as such in the README rather than implied
        // otherwise.
        long p50 = durationsMs.get((int) Math.ceil(durationsMs.size() * 0.5) - 1);
        long p95 = durationsMs.get((int) Math.ceil(durationsMs.size() * 0.95) - 1);
        long max = durationsMs.get(durationsMs.size() - 1);
        System.out.printf(
                "Day 12 load test - %d documents, end-to-end duration (ms): p50=%d p95=%d max=%d all=%s%n",
                DOCUMENT_COUNT, p50, p95, max, durationsMs);
        System.out.printf("Day 12 load test - durations (ms) in document creation order: %s%n",
                durationsInArrivalOrderMs);

        assertThat(p95).as("p95 end-to-end duration (ms) over %s: a stall/regression detector, not an SLO", durationsMs)
                .isLessThan(P95_SANITY_CEILING_MS);
    }

    private UUID uploadOnePagePdf() throws Exception {
        byte[] pdf = TestPdfs.blank(1);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Load test letter");
        body.add("language", "hi");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        TestAuth.addCsrf(headers, restTemplate);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<DocumentUploadResponse> response = restTemplate.postForEntity(
                "/api/documents", request, DocumentUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().id();
    }

    private void stubDigitiseSubmit() {
        wireMockServer.stubFor(post(urlPathEqualTo("/doc-ai/v1/job/digitise"))
                .willReturn(okJson("{\"job_id\": \"{{randomValue type='UUID'}}\"}")));
    }

    private void stubStatusCompleted() {
        wireMockServer.stubFor(get(urlPathMatching("/doc-ai/v1/job/[^/]+/status"))
                .willReturn(okJson("{\"job_id\": \"unused\", \"status\": \"completed\"}")));
    }

    private void stubDownload(byte[] resultZip) {
        wireMockServer.stubFor(get(urlPathMatching("/doc-ai/v1/job/[^/]+/download-url"))
                .willReturn(okJson("{\"download_url\": \"%s/results/shared.zip\"}".formatted(wireMockServer.baseUrl()))));
        wireMockServer.stubFor(get(urlPathEqualTo("/results/shared.zip"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(resultZip)));
    }

    private void stubTranslate() {
        wireMockServer.stubFor(post(urlPathEqualTo("/translate"))
                .willReturn(okJson("{\"translated_text\": \"Load test translated\"}")));
    }

    private void stubTextToSpeech() {
        // A tiny, deliberately silent-but-valid WAV: the assembler only
        // needs to concatenate and write bytes, not play them back, and a
        // real synthesized clip would cost this test nothing but runtime.
        byte[] silentWav = minimalWav();
        String base64Wav = java.util.Base64.getEncoder().encodeToString(silentWav);
        wireMockServer.stubFor(post(urlPathEqualTo("/text-to-speech"))
                .willReturn(okJson("{\"request_id\": \"req-load\", \"audios\": [\"%s\"]}".formatted(base64Wav))));
    }

    private static byte[] minimalWav() {
        try {
            javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(22050f, 16, 1, true, false);
            byte[] data = new byte[10 * format.getFrameSize()];
            try (javax.sound.sampled.AudioInputStream stream = new javax.sound.sampled.AudioInputStream(
                    new java.io.ByteArrayInputStream(data), format, 10)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                javax.sound.sampled.AudioSystem.write(stream, javax.sound.sampled.AudioFileFormat.Type.WAVE, out);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
