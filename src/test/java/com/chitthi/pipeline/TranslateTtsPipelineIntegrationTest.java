package com.chitthi.pipeline;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.web.AudioUrlResponse;
import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.storage.ObjectStorageService;
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

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
 * The Day 5-6 acceptance criterion: one document plays in both languages.
 * Builds on {@code OcrPipelineIntegrationTest}'s pattern - real Postgres and
 * RabbitMQ plus LocalStack's S3 service standing in for MinIO via
 * Testcontainers, only Sarvam stubbed - and carries
 * a 12-page Hindi PDF all the way through OCR, translate, TTS and assembly to
 * a COMPLETE document with two playable audio tracks.
 *
 * <p>Every page's OCR'd, translated and synthesized text is a short, fixed
 * stub value, so each page needs exactly one chunk per stage: one translate
 * call and two TTS calls (orig + en, since Hindi is Bulbul-supported) per
 * page - 12 and 24 total - which is asserted directly as the embryo of the
 * "0 duplicate paid API calls" success metric extended to these two stages.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.poller.sweep-interval-ms=200",
        "chitthi.ocr.poll.initial-delay=200ms",
        "chitthi.ocr.poll.max-delay=500ms",
        "chitthi.ocr.poll.jitter-ratio=0"
})
class TranslateTtsPipelineIntegrationTest {

    private static final String TRANSLATED_TEXT = "TRANSLATED";
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

    @Autowired
    ObjectStorageService storageService;

    @Test
    void twelvePageDocument_completesAndPlaysInBothLanguages() throws IOException {
        stubDigitiseSubmit("_1-10.zip", "job-1-10");
        stubDigitiseSubmit("_11-12.zip", "job-11-12");
        stubStatusCompleted("job-1-10");
        stubStatusCompleted("job-11-12");
        stubDownload("job-1-10", DigitiseResultZips.perPageJson(10, i -> "Chunk text page " + i));
        stubDownload("job-11-12", DigitiseResultZips.perPageJson(2, i -> "Second chunk page " + i));
        stubTranslate();
        stubTextToSpeech();

        UUID documentId = uploadTwelvePagePdf();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETE);
        });

        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);
        assertThat(pages).hasSize(12);
        assertThat(pages).allSatisfy(page -> {
            assertThat(page.getStatus()).isEqualTo(PageStatus.INDEXED);
            assertThat(page.getTranslatedText()).isEqualTo(TRANSLATED_TEXT);
        });

        assertTrackHasExpectedFrameCount(documentId, "orig");
        assertTrackHasExpectedFrameCount(documentId, "en");

        assertAudioEndpointWorks(documentId, "orig", false);
        assertAudioEndpointWorks(documentId, "en", false);

        wireMockServer.verify(12, postRequestedFor(urlPathEqualTo("/translate")));
        // 12 for the orig track (every page's original text is distinct) plus
        // 1 for the en track: every page translates to the same stubbed
        // TRANSLATED_TEXT, so Day 10's owner-scoped TTS cache (same owner,
        // same voice, same text) collapses what would otherwise be 12 calls
        // into 1 - proving FR13 end to end, not just in isolation.
        wireMockServer.verify(13, postRequestedFor(urlPathEqualTo("/text-to-speech")));
    }

    /**
     * The Day 7 acceptance criterion: you can watch pages move through the
     * stages live. Opens the SSE stream right after upload and reads it with
     * the JDK's own HTTP client - a real request, not a mocked one - so the
     * assertion exercises {@code ProgressBroadcaster} publishing snapshots,
     * {@code SseEmitterRegistry} delivering them, and the stream completing
     * itself once the document reaches COMPLETE, exactly as a browser would
     * see it.
     *
     * <p>Deliberately a single page, not the 12-page document above: this
     * test proves the streaming mechanism, not the Day 5-6 pipeline itself,
     * and running the full 12-page pipeline twice in one test class pushed a
     * CI runner into resource pressure that crashed its Postgres container.
     */
    @Test
    void sseStream_deliversProgressUntilTheDocumentCompletes() throws IOException, InterruptedException {
        stubDigitiseSubmit("_1-1.zip", "job-1-1");
        stubStatusCompleted("job-1-1");
        stubDownload("job-1-1", DigitiseResultZips.perPageJson(1, i -> "Only page text"));
        stubTranslate();
        stubTextToSpeech();

        UUID documentId = uploadOnePagePdf();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/documents/%s/events".formatted(port, documentId)))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        boolean[] sawComplete = {false};
        try (Stream<String> lines = response.body()) {
            // The stream ends on its own once the server-side emitter
            // completes - iterating to exhaustion is exactly the assertion
            // that the stream closes after the terminal snapshot, not just
            // that one arrived.
            lines.forEach(line -> {
                if (line.startsWith("data:") && line.contains("\"status\":\"COMPLETE\"")) {
                    sawComplete[0] = true;
                }
            });
        }

        assertThat(sawComplete[0]).isTrue();
    }

    private void assertTrackHasExpectedFrameCount(UUID documentId, String track) throws IOException {
        byte[] trackWav = storageService.getObject("documents/%s/audio/%s.wav".formatted(documentId, track));
        try {
            javax.sound.sampled.AudioInputStream stream =
                    AudioSystem.getAudioInputStream(new ByteArrayInputStream(trackWav));
            assertThat(stream.getFrameLength()).isEqualTo(12L * STUB_WAV_FRAMES);
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException(e);
        }
    }

    private void assertAudioEndpointWorks(UUID documentId, String lang, boolean expectedFallback) {
        ResponseEntity<AudioUrlResponse> response = restTemplate.getForEntity(
                "/api/documents/{id}/audio?lang={lang}", AudioUrlResponse.class, documentId, lang);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().url()).isNotBlank();
        assertThat(response.getBody().fallback()).isEqualTo(expectedFallback);
    }

    private UUID uploadTwelvePagePdf() throws IOException {
        return uploadPdf(12);
    }

    private UUID uploadOnePagePdf() throws IOException {
        return uploadPdf(1);
    }

    private UUID uploadPdf(int pageCount) throws IOException {
        byte[] pdf = buildBlankPdf(pageCount);

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
                .willReturn(okJson("{\"translated_text\": \"%s\"}".formatted(TRANSLATED_TEXT))));
    }

    private void stubTextToSpeech() {
        String base64Wav = Base64.getEncoder().encodeToString(generateWav());
        wireMockServer.stubFor(post(urlPathEqualTo("/text-to-speech"))
                .willReturn(okJson("{\"request_id\": \"req-1\", \"audios\": [\"%s\"]}".formatted(base64Wav))));
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
