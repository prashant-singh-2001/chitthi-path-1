package com.chitthi.sarvam;

import com.chitthi.sarvam.dto.DigitiseJobResponse;
import com.chitthi.sarvam.dto.DownloadUrlResponse;
import com.chitthi.sarvam.dto.JobStatus;
import com.chitthi.sarvam.dto.JobStatusResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Makes one <b>real, paid</b> call against the live Sarvam API to pin the
 * Digitise result ZIP's actual shape - specifically, which JSON field inside
 * {@code metadata/page_NNN.json} holds a page's text
 * ({@link com.chitthi.ocr.result.PageTextExtractor} guesses at this from a
 * configured candidate list because the public docs don't say).
 *
 * <p>Excluded from the default build (see the surefire {@code excludedGroups}
 * in {@code pom.xml}) and gated on {@code SARVAM_API_KEY} being set, so
 * {@code mvn verify} and CI never spend credits. Run it deliberately with
 * {@code mvn test -Dgroups=smoke -DSARVAM_API_KEY=...} (or the env var set),
 * read what it prints, and if the real field name isn't already in
 * {@code chitthi.ocr.result.text-fields}, add it there - no code change
 * needed.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "SARVAM_API_KEY", matches = ".+")
class SarvamDigitiseSmokeTest {

    @Test
    void submitOnePage_andPrintTheResultZipShape() throws IOException, InterruptedException {
        SarvamClient sarvamClient = buildRealSarvamClient();

        byte[] payload = buildOnePagePayload();
        DigitiseJobResponse submitted = sarvamClient.submitDigitiseJob(payload, "chitthi-smoke-test.zip", "hi");
        System.out.println("Submitted Digitise job: " + submitted.jobId());

        JobStatusResponse finalStatus = pollUntilDone(sarvamClient, submitted.jobId());
        System.out.println("Final job status: " + finalStatus.status());
        assumeTrue(finalStatus.status() == JobStatus.COMPLETED || finalStatus.status() == JobStatus.PARTIALLY_COMPLETED,
                "Job did not reach a completed state: " + finalStatus.status());

        DownloadUrlResponse downloadUrlResponse = sarvamClient.getDownloadUrl(submitted.jobId());
        byte[] resultZip = sarvamClient.downloadResult(downloadUrlResponse.downloadUrl());

        System.out.println("=== Result ZIP entries ===");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(resultZip))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] content = zip.readAllBytes();
                System.out.println(entry.getName() + " (" + content.length + " bytes)");
                if (entry.getName().toLowerCase().contains("page_001") || entry.getName().equalsIgnoreCase("manifest.json")) {
                    System.out.println("--- content ---");
                    System.out.println(new String(content, java.nio.charset.StandardCharsets.UTF_8));
                    System.out.println("---------------");
                }
            }
        }
    }

    private SarvamClient buildRealSarvamClient() {
        SarvamProperties properties = new SarvamProperties(
                "https://api.sarvam.ai",
                System.getenv("SARVAM_API_KEY"),
                new SarvamProperties.Http(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60)),
            new SarvamProperties.RateLimits(10, 60, 60),
                new SarvamProperties.Pipeline(10, 2000, 2500, 5),
                new SarvamProperties.Translate("sarvam-translate:v1"),
                new SarvamProperties.Tts("bulbul:v3", "shubh", 22050));

        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("api-subscription-key", properties.apiSubscriptionKey())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        RestClient downloadRestClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        return new SarvamClient(restClient, downloadRestClient, properties, new com.chitthi.sarvam.SarvamResilience(properties));
    }

    /** A single generated page with readable text, zipped exactly as {@code OcrPayloadPackager} would produce. */
    private byte[] buildOnePagePayload() throws IOException {
        BufferedImage image = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 300);
        graphics.setColor(Color.BLACK);
        graphics.drawString("Chitthi smoke test page - Digitise output shape probe", 20, 150);
        graphics.dispose();

        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngBytes);

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("page_001.png"));
            zip.write(pngBytes.toByteArray());
            zip.closeEntry();
        }
        return zipBytes.toByteArray();
    }

    private JobStatusResponse pollUntilDone(SarvamClient sarvamClient, String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 24; attempt++) {
            JobStatusResponse status = sarvamClient.getJobStatus(jobId);
            if (status.status() != JobStatus.PENDING && status.status() != JobStatus.RUNNING) {
                return status;
            }
            Thread.sleep(5000);
        }
        throw new AssertionError("Digitise job did not finish within the smoke test's poll budget (~2 minutes)");
    }
}
