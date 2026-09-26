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

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Day 10 acceptance criterion: editing page 3 re-runs only page 3. Every
 * page gets distinct OCR and translated text, so a translate or TTS request
 * can be attributed to exactly one page by its body - proving an edit's
 * regeneration never touches an unrelated page, and reverting an edit costs
 * nothing thanks to Day 8-9's stage_task cache.
 *
 * <p>A 3-page document, not more: the Day 7 CI incident showed a shared
 * Testcontainers runner can crash under a heavy pipeline run, and this test
 * already exercises the full OCR-translate-TTS-assemble cycle twice (once
 * before the edit, once after).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.poller.sweep-interval-ms=200",
        "chitthi.ocr.poll.initial-delay=200ms",
        "chitthi.ocr.poll.max-delay=500ms",
        "chitthi.ocr.poll.jitter-ratio=0",
        "chitthi.outbox.relay-interval-ms=100"
})
class EditFlowIntegrationTest {

    private static final int STUB_WAV_FRAMES = 50;
    private static final int PAGE_COUNT = 3;

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

    @Autowired
    com.chitthi.storage.ObjectStorageService storageService;

    @Test
    void editingPageThreeReRunsOnlyPageThree() throws IOException {
        stubDigitiseSubmit("job-edit");
        stubStatusCompleted("job-edit");
        stubDownload("job-edit", DigitiseResultZips.perPageJson(PAGE_COUNT, i -> "Page " + i + " text"));
        stubTranslateFor("Page 1 text", "Page 1 EN");
        stubTranslateFor("Page 2 text", "Page 2 EN");
        stubTranslateFor("Page 3 text", "Page 3 EN");
        stubTextToSpeech();

        UUID documentId = uploadThreePagePdf();

        awaitDocumentStatus(documentId, DocumentStatus.COMPLETE);
        assertThat(pageRepository.findByDocumentIdOrderByPageNo(documentId))
                .allSatisfy(page -> assertThat(page.getStatus()).isEqualTo(PageStatus.INDEXED));
        assertTrackHasExpectedFrameCount(documentId, "orig");
        assertTrackHasExpectedFrameCount(documentId, "en");

        // --- Edit page 3 ---
        // Clears the journal (not the stubs), so "no request mentions page 1
        // or 2" below only has to be true for what happens from here on -
        // the initial pipeline run above obviously did request all three.
        wireMockServer.resetRequests();
        stubTranslateFor("Page 3 EDITED text", "Page 3 EDITED EN");

        ResponseEntity<Object> editResponse = restTemplate.exchange(
                "/api/documents/{id}/pages/{pageNo}/text", HttpMethod.PUT,
                new HttpEntity<>(Map.of("text", "Page 3 EDITED text")), Object.class,
                documentId, 3);
        assertThat(editResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        awaitDocumentStatus(documentId, DocumentStatus.COMPLETE);

        Page page1 = findPage(documentId, 1);
        Page page2 = findPage(documentId, 2);
        Page page3 = findPage(documentId, 3);
        assertThat(page1.isEdited()).isFalse();
        assertThat(page2.isEdited()).isFalse();
        assertThat(page3.isEdited()).isTrue();
        assertThat(page3.getOriginalText()).isEqualTo("Page 3 EDITED text");
        assertThat(page3.getTranslatedText()).isEqualTo("Page 3 EDITED EN");
        assertThat(page1.getStatus()).isEqualTo(PageStatus.INDEXED);
        assertThat(page2.getStatus()).isEqualTo(PageStatus.INDEXED);
        assertThat(page3.getStatus()).isEqualTo(PageStatus.INDEXED);

        // Exactly one new translate call, carrying page 3's new text.
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/translate"))
                .withRequestBody(containing("Page 3 EDITED text")));
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/translate")).withRequestBody(containing("Page 1")));
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/translate")).withRequestBody(containing("Page 2")));

        // Exactly two new TTS calls (orig + en), both for page 3's new text.
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/text-to-speech"))
                .withRequestBody(containing("Page 3 EDITED text")));
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/text-to-speech"))
                .withRequestBody(containing("Page 3 EDITED EN")));
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/text-to-speech")).withRequestBody(containing("Page 1")));
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/text-to-speech")).withRequestBody(containing("Page 2")));

        assertTrackHasExpectedFrameCount(documentId, "orig");
        assertTrackHasExpectedFrameCount(documentId, "en");

        // --- Revert page 3 back to its original text ---
        wireMockServer.resetRequests();

        ResponseEntity<Object> revertResponse = restTemplate.exchange(
                "/api/documents/{id}/pages/{pageNo}/text", HttpMethod.PUT,
                new HttpEntity<>(Map.of("text", "Page 3 text")), Object.class,
                documentId, 3);
        assertThat(revertResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        awaitDocumentStatus(documentId, DocumentStatus.COMPLETE);
        Page revertedPage3 = findPage(documentId, 3);
        assertThat(revertedPage3.getOriginalText()).isEqualTo("Page 3 text");
        assertThat(revertedPage3.getTranslatedText()).isEqualTo("Page 3 EN");
        assertThat(revertedPage3.getStatus()).isEqualTo(PageStatus.INDEXED);

        // The revert's translate and TTS chunks were already DONE from the
        // very first pass - repeated regeneration costs nothing.
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/translate")));
        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/text-to-speech")));
    }

    @Test
    void editingAPendingPageIsRejectedWith409() {
        // No OCR stub at all - the page never leaves PENDING.
        UUID documentId = uploadThreePagePdf();

        ResponseEntity<Object> response = restTemplate.exchange(
                "/api/documents/{id}/pages/{pageNo}/text", HttpMethod.PUT,
                new HttpEntity<>(Map.of("text", "too soon")), Object.class,
                documentId, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void editingAMissingDocumentIsRejectedWith404() {
        ResponseEntity<Object> response = restTemplate.exchange(
                "/api/documents/{id}/pages/{pageNo}/text", HttpMethod.PUT,
                new HttpEntity<>(Map.of("text", "text")), Object.class,
                UUID.randomUUID(), 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    private UUID uploadThreePagePdf() {
        byte[] pdf = buildBlankPdf(PAGE_COUNT);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Edit flow test letter");
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

    /** A new, higher-priority stub per distinct input text - lets every page's translate call return its own output. */
    private void stubTranslateFor(String inputText, String translatedText) {
        wireMockServer.stubFor(post(urlPathEqualTo("/translate"))
                .withRequestBody(matchingJsonPath("$.input", com.github.tomakehurst.wiremock.client.WireMock.equalTo(inputText)))
                .willReturn(okJson("{\"translated_text\": \"%s\"}".formatted(translatedText))));
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

    private void assertTrackHasExpectedFrameCount(UUID documentId, String track) throws IOException {
        byte[] trackWav = getObjectFromStorage(documentId, track);
        try {
            AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(trackWav));
            assertThat(stream.getFrameLength()).isEqualTo((long) PAGE_COUNT * STUB_WAV_FRAMES);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException(e);
        }
    }

    private byte[] getObjectFromStorage(UUID documentId, String track) {
        return storageService.getObject("documents/%s/audio/%s.wav".formatted(documentId, track));
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
