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
        sarvamClient = new SarvamClient(restClient, downloadRestClient);
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
}
