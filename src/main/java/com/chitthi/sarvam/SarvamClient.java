package com.chitthi.sarvam;

import com.chitthi.sarvam.dto.DigitiseJobResponse;
import com.chitthi.sarvam.dto.DownloadUrlResponse;
import com.chitthi.sarvam.dto.JobStatusResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Thin typed wrapper around the Sarvam REST API. All outbound Sarvam calls go
 * through here, so a future API shape change (Document AI replaced Document
 * Digitization once already) is a one-file fix.
 */
@Component
public class SarvamClient {

    private final RestClient restClient;

    public SarvamClient(RestClient sarvamRestClient) {
        this.restClient = sarvamRestClient;
    }

    /**
     * Submits a chunk of up to 10 pages (PDF or ZIP of images) to Document AI
     * Digitise. Sarvam requires the source language up front.
     */
    public DigitiseJobResponse submitDigitiseJob(byte[] fileContent, String filename, String language) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("language", language);
        body.add("output_format", "md");

        return restClient.post()
                .uri("/doc-ai/v1/job/digitise")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(DigitiseJobResponse.class);
    }

    public JobStatusResponse getJobStatus(String jobId) {
        return restClient.get()
                .uri("/doc-ai/v1/job/{id}/status", jobId)
                .retrieve()
                .body(JobStatusResponse.class);
    }

    public DownloadUrlResponse getDownloadUrl(String jobId) {
        return restClient.get()
                .uri("/doc-ai/v1/job/{id}/download-url", jobId)
                .retrieve()
                .body(DownloadUrlResponse.class);
    }
}
