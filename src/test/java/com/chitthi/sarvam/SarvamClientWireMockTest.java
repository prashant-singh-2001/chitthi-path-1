package com.chitthi.sarvam;

import com.chitthi.sarvam.dto.DigitiseJobResponse;
import com.chitthi.sarvam.dto.DownloadUrlResponse;
import com.chitthi.sarvam.dto.JobStatus;
import com.chitthi.sarvam.dto.JobStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * Pins the shape of the Sarvam Document AI Digitise contract so an upstream
 * API change breaks this test first, not a real (paid) call in production.
 */
class SarvamClientWireMockTest {

    private WireMockServer wireMockServer;
    private SarvamClient sarvamClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .defaultHeader("api-subscription-key", "test-key")
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        RestClient downloadRestClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        SarvamProperties properties = new SarvamProperties(
                wireMockServer.baseUrl(), "test-key",
                new SarvamProperties.Http(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60)),
            new SarvamProperties.RateLimits(10, 60, 60),
                new SarvamProperties.Pipeline(10, 2000, 2500, 5),
                new SarvamProperties.Translate("sarvam-translate:v1"),
                new SarvamProperties.Tts("bulbul:v3", "shubh", 22050));
        sarvamClient = new SarvamClient(restClient, downloadRestClient, properties, new com.chitthi.sarvam.SarvamResilience(properties));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void submitDigitiseJob_parsesJobId() {
        wireMockServer.stubFor(post(urlPathMatching("/doc-ai/v1/job/digitise"))
                .withHeader("api-subscription-key", equalTo("test-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\": \"job-123\"}")));

        DigitiseJobResponse response = sarvamClient.submitDigitiseJob(
                "fake-pdf-bytes".getBytes(), "letter.pdf", "hi");

        assertThat(response.jobId()).isEqualTo("job-123");
    }

    @Test
    void getJobStatus_mapsPartiallyCompleted() {
        wireMockServer.stubFor(get(urlPathMatching("/doc-ai/v1/job/job-123/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\": \"job-123\", \"status\": \"partially_completed\"}")));

        JobStatusResponse response = sarvamClient.getJobStatus("job-123");

        assertThat(response.jobId()).isEqualTo("job-123");
        assertThat(response.status()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);
    }

    @Test
    void getDownloadUrl_parsesUrl() {
        wireMockServer.stubFor(get(urlPathMatching("/doc-ai/v1/job/job-123/download-url"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"download_url\": \"https://storage.sarvam.ai/job-123.zip\"}")));

        DownloadUrlResponse response = sarvamClient.getDownloadUrl("job-123");

        assertThat(response.downloadUrl()).isEqualTo("https://storage.sarvam.ai/job-123.zip");
    }

    @Test
    void downloadResult_fetchesBytesWithoutTheSubscriptionKeyHeader() {
        wireMockServer.stubFor(get(urlPathMatching("/results/job-123.zip"))
                .withHeader("api-subscription-key", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody("fake-zip-bytes".getBytes())));

        byte[] result = sarvamClient.downloadResult(wireMockServer.baseUrl() + "/results/job-123.zip");

        assertThat(result).isEqualTo("fake-zip-bytes".getBytes());
    }

    @Test
    void translate_sendsTheModelAndLanguagePairAndParsesTranslatedText() {
        wireMockServer.stubFor(post(urlPathMatching("/translate"))
                .withHeader("api-subscription-key", equalTo("test-key"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.model", equalTo("sarvam-translate:v1")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.source_language_code", equalTo("hi-IN")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.target_language_code", equalTo("en-IN")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"translated_text\": \"hello\"}")));

        String result = sarvamClient.translate("नमस्ते", "hi-IN", "en-IN");

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void synthesize_decodesTheFirstBase64AudioEntry() {
        String base64Audio = java.util.Base64.getEncoder().encodeToString("fake-wav-bytes".getBytes());
        wireMockServer.stubFor(post(urlPathMatching("/text-to-speech"))
                .withHeader("api-subscription-key", equalTo("test-key"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.model", equalTo("bulbul:v3")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.speaker", equalTo("shubh")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"request_id\": \"req-1\", \"audios\": [\"" + base64Audio + "\"]}")));

        byte[] result = sarvamClient.synthesize("hello", "en-IN");

        assertThat(result).isEqualTo("fake-wav-bytes".getBytes());
    }

    @Test
    void translate_retriesA429UsingRetryAfterAndSucceedsOnTheNextAttempt() {
        String scenario = "translate-429-then-ok";
        wireMockServer.stubFor(post(urlPathMatching("/translate"))
                .inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "1"))
                .willSetStateTo("retried"));
        wireMockServer.stubFor(post(urlPathMatching("/translate"))
                .inScenario(scenario)
                .whenScenarioStateIs("retried")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"translated_text\": \"hello\"}")));

        String result = sarvamClient.translate("नमस्ते", "hi-IN", "en-IN");

        assertThat(result).isEqualTo("hello");
        wireMockServer.verify(2, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathMatching("/translate")));
    }

    @Test
    void translate_givesUpAfterExhaustingRetriesOnRepeated429s() {
        wireMockServer.stubFor(post(urlPathMatching("/translate"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0")));

        org.junit.jupiter.api.Assertions.assertThrows(SarvamRateLimitedException.class,
                () -> sarvamClient.translate("नमस्ते", "hi-IN", "en-IN"));

        // maxAttempts(4) in SarvamResilience: the original call plus 3 retries.
        wireMockServer.verify(4, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathMatching("/translate")));
    }
}
