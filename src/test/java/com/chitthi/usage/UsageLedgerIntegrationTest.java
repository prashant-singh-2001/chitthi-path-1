package com.chitthi.usage;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.web.DocumentUploadResponse;
import com.chitthi.support.DigitiseResultZips;
import com.chitthi.usage.web.DocumentUsageView;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR10's acceptance criterion: cost per document is visible. A 2-page
 * document with distinct text per page (so translate and TTS never share an
 * owner-scoped cache key) produces exactly the ledger rows the pipeline
 * actually made, at exactly the configured per-unit prices.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.poller.sweep-interval-ms=200",
        "chitthi.ocr.poll.initial-delay=200ms",
        "chitthi.ocr.poll.max-delay=500ms",
        "chitthi.ocr.poll.jitter-ratio=0",
        "chitthi.outbox.relay-interval-ms=100"
})
class UsageLedgerIntegrationTest {

    private static final int STUB_WAV_FRAMES = 50;
    private static final String PAGE1_ORIGINAL = "Original text for page one";
    private static final String PAGE1_TRANSLATED = "Translated text for page one";
    private static final String PAGE2_ORIGINAL = "Original text for page two";
    private static final String PAGE2_TRANSLATED = "Translated text for page two";

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
    DocumentRepository documentRepository;

    @Autowired
    ApiCallRepository apiCallRepository;

    @Autowired
    UsagePricing pricing;

    @Test
    void everyPaidCallIsLedgeredAtItsConfiguredPrice() {
        stubDigitiseSubmit("job-usage");
        stubStatusCompleted("job-usage");
        stubDownload("job-usage", DigitiseResultZips.perPageJson(2, i -> i == 1 ? PAGE1_ORIGINAL : PAGE2_ORIGINAL));
        stubTranslateFor(PAGE1_ORIGINAL, PAGE1_TRANSLATED);
        stubTranslateFor(PAGE2_ORIGINAL, PAGE2_TRANSLATED);
        stubTextToSpeech();

        UUID documentId = uploadTwoPagePdf();

        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Document document = documentRepository.findById(documentId).orElseThrow();
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETE);
        });

        List<ApiCall> calls = apiCallRepository.findAll().stream()
                .filter(call -> call.getDocumentId().equals(documentId))
                .toList();

        List<ApiCall> visionCalls = byEndpoint(calls, "vision-submit");
        assertThat(visionCalls).hasSize(1);
        assertThat(visionCalls.get(0).getUnits()).isEqualTo(2);
        assertThat(visionCalls.get(0).getEstCostInr()).isEqualByComparingTo(pricing.visionPerPage().multiply(BigDecimal.valueOf(2)));

        List<ApiCall> translateCalls = byEndpoint(calls, "translate");
        assertThat(translateCalls).hasSize(2);
        BigDecimal expectedTranslateCost = pricing.translatePerChar()
                .multiply(BigDecimal.valueOf(PAGE1_ORIGINAL.length() + PAGE2_ORIGINAL.length()));
        BigDecimal actualTranslateCost = translateCalls.stream().map(ApiCall::getEstCostInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualTranslateCost).isEqualByComparingTo(expectedTranslateCost);

        // Both tracks (orig + en) for both pages - 4 distinct texts, so
        // none of them collide under the owner-scoped TTS cache.
        List<ApiCall> ttsCalls = byEndpoint(calls, "tts");
        assertThat(ttsCalls).hasSize(4);
        int totalTtsChars = PAGE1_ORIGINAL.length() + PAGE1_TRANSLATED.length()
                + PAGE2_ORIGINAL.length() + PAGE2_TRANSLATED.length();
        BigDecimal expectedTtsCost = pricing.ttsPerChar().multiply(BigDecimal.valueOf(totalTtsChars));
        BigDecimal actualTtsCost = ttsCalls.stream().map(ApiCall::getEstCostInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualTtsCost).isEqualByComparingTo(expectedTtsCost);

        ResponseEntity<DocumentUsageView> usageResponse = restTemplate.getForEntity(
                "/api/documents/{id}/usage", DocumentUsageView.class, documentId);
        assertThat(usageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        BigDecimal expectedTotal = visionCalls.get(0).getEstCostInr().add(expectedTranslateCost).add(expectedTtsCost);
        assertThat(usageResponse.getBody().totalCostInr()).isEqualByComparingTo(expectedTotal);
        assertThat(usageResponse.getBody().byEndpoint()).hasSize(3);
    }

    private List<ApiCall> byEndpoint(List<ApiCall> calls, String endpoint) {
        return calls.stream().filter(call -> call.getEndpoint().equals(endpoint)).toList();
    }

    private UUID uploadTwoPagePdf() {
        byte[] pdf = buildBlankPdf(2);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "letter.pdf";
            }
        });
        body.add("title", "Usage ledger test letter");
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

    private void stubTranslateFor(String inputText, String translatedText) {
        wireMockServer.stubFor(post(urlPathEqualTo("/translate"))
                .withRequestBody(matchingJsonPath("$.input", equalTo(inputText)))
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
