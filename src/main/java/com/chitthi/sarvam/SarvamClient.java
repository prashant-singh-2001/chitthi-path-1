package com.chitthi.sarvam;

import com.chitthi.sarvam.dto.DigitiseJobResponse;
import com.chitthi.sarvam.dto.DownloadUrlResponse;
import com.chitthi.sarvam.dto.JobStatusResponse;
import com.chitthi.sarvam.dto.TranslateRequest;
import com.chitthi.sarvam.dto.TranslateResponse;
import com.chitthi.sarvam.dto.TtsRequest;
import com.chitthi.sarvam.dto.TtsResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.function.Supplier;

/**
 * Thin typed wrapper around the Sarvam REST API. All outbound Sarvam calls go
 * through here, so a future API shape change (Document AI replaced Document
 * Digitization once already) is a one-file fix.
 *
 * <p>Every paid call runs through {@link SarvamResilience}: a 429 is
 * translated to {@link SarvamRateLimitedException} and retried using
 * Sarvam's own {@code Retry-After}, and a real failure (5xx, IO, timeout)
 * counts toward that endpoint's circuit breaker. Callers that see
 * {@code RequestNotPermitted} or {@code CallNotPermittedException} escape
 * from this class should treat the call as never having been made - see
 * {@code OcrWorker} and {@code PipelineMessageRecoverer} for how those are
 * turned into "wait, don't burn a retry" rather than a failure.
 */
@Component
public class SarvamClient {

    private final RestClient restClient;
    private final RestClient downloadRestClient;
    private final SarvamProperties properties;
    private final SarvamResilience resilience;

    public SarvamClient(RestClient sarvamRestClient, RestClient sarvamDownloadRestClient,
                         SarvamProperties properties, SarvamResilience resilience) {
        this.restClient = sarvamRestClient;
        this.downloadRestClient = sarvamDownloadRestClient;
        this.properties = properties;
        this.resilience = resilience;
    }

    /**
     * Submits a chunk of up to 10 pages (PDF or ZIP of images) to Document AI
     * Digitise. Sarvam requires the source language up front.
     */
    public DigitiseJobResponse submitDigitiseJob(byte[] fileContent, String filename, String language) {
        return resilience.execute(SarvamResilience.VISION_SUBMIT, () -> callSubmitDigitiseJob(fileContent, filename, language));
    }

    private DigitiseJobResponse callSubmitDigitiseJob(byte[] fileContent, String filename, String language) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("language", language);
        body.add("output_format", "md");

        return rethrowingRateLimit(() -> restClient.post()
                .uri("/doc-ai/v1/job/digitise")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(DigitiseJobResponse.class));
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

    /**
     * Fetches the Digitise result ZIP from the presigned URL returned by
     * {@link #getDownloadUrl}. Deliberately uses a separate client with no
     * {@code api-subscription-key} header - that URL points at Sarvam's
     * storage host, not {@code sarvam.base-url}, and the key must not be sent
     * to a third party.
     */
    public byte[] downloadResult(String downloadUrl) {
        return downloadRestClient.get()
                .uri(java.net.URI.create(downloadUrl))
                .retrieve()
                .body(byte[].class);
    }

    /**
     * Translates one chunk of text (up to Translate's 2,000-char limit,
     * enforced by the caller via {@code SentenceChunker}) between two
     * {@code xx-IN} language codes.
     */
    public String translate(String text, String sourceLanguageCode, String targetLanguageCode) {
        return resilience.execute(SarvamResilience.TRANSLATE,
                () -> callTranslate(text, sourceLanguageCode, targetLanguageCode));
    }

    private String callTranslate(String text, String sourceLanguageCode, String targetLanguageCode) {
        TranslateRequest request = new TranslateRequest(
                text, sourceLanguageCode, targetLanguageCode, properties.translate().model());
        TranslateResponse response = rethrowingRateLimit(() -> restClient.post()
                .uri("/translate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TranslateResponse.class));
        return response.translatedText();
    }

    /**
     * Synthesizes one chunk of text (up to TTS's 2,500-char limit, enforced
     * by the caller) into WAV audio at the configured sample rate, decoding
     * Sarvam's single base64-encoded {@code audios[0]} entry.
     */
    public byte[] synthesize(String text, String languageCode) {
        return resilience.execute(SarvamResilience.TTS, () -> callSynthesize(text, languageCode));
    }

    private byte[] callSynthesize(String text, String languageCode) {
        TtsRequest request = new TtsRequest(
                text, languageCode, properties.tts().speaker(), properties.tts().model(),
                properties.tts().sampleRate(), "wav");
        TtsResponse response = rethrowingRateLimit(() -> restClient.post()
                .uri("/text-to-speech")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TtsResponse.class));
        return Base64.getDecoder().decode(response.audios().get(0));
    }

    /**
     * Runs one HTTP call, turning a 429 into {@link SarvamRateLimitedException}
     * carrying Sarvam's {@code Retry-After} (seconds), so
     * {@link SarvamResilience}'s retry can back off by exactly that long
     * instead of a guessed interval.
     */
    private <T> T rethrowingRateLimit(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new SarvamRateLimitedException(parseRetryAfterMillis(e));
        }
    }

    private static long parseRetryAfterMillis(HttpClientErrorException e) {
        HttpHeaders headers = e.getResponseHeaders();
        String header = headers != null ? headers.getFirst(HttpHeaders.RETRY_AFTER) : null;
        if (header != null) {
            try {
                return Long.parseLong(header.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // Fall through to the default below.
            }
        }
        return 1000L;
    }
}
