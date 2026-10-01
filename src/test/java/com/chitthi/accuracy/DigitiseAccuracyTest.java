package com.chitthi.accuracy;

import com.chitthi.ocr.OcrProperties;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.ocr.result.DigitiseResultParser;
import com.chitthi.ocr.result.PageTextExtractor;
import com.chitthi.ocr.result.ParsedPage;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.SarvamProperties;
import com.chitthi.sarvam.SarvamResilience;
import com.chitthi.sarvam.dto.DigitiseJobResponse;
import com.chitthi.sarvam.dto.JobStatus;
import com.chitthi.sarvam.dto.JobStatusResponse;
import com.chitthi.usage.UsagePricing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Measures how accurately Sarvam's Document AI Digitise reads <b>real
 * handwriting</b>, by comparing its output with a human transcription. Makes
 * <b>real, paid</b> calls: one Digitise job per image.
 *
 * <p>Opt-in on two counts: it needs {@code SARVAM_API_KEY} and
 * {@code CHITTHI_ACCURACY_DIR}, and it is excluded from the default build
 * (surefire {@code excludedGroups} in {@code pom.xml}). Run it with
 * {@code mvn test -Paccuracy}. No Spring context and no Docker.
 *
 * <p>Input layout: {@code <CHITTHI_ACCURACY_DIR>/<language>/<name>.jpg|jpeg|png}
 * with the exact transcription, in the original script, in
 * {@code <name>.txt} beside it. The directory name is sent as the language
 * code (e.g. {@code hi}, {@code ta}). Keep the scans outside the repo, and
 * public-domain only.
 *
 * <p>The output goes through the real {@link DigitiseResultParser}, configured
 * from {@code application.yml}'s {@code chitthi.ocr}, so the figures describe
 * what the app would actually show a user. The report also names the JSON
 * field the text came from, which pins the field the parser otherwise guesses.
 */
@Tag("accuracy")
@EnabledIfEnvironmentVariable(named = "SARVAM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CHITTHI_ACCURACY_DIR", matches = ".+")
class DigitiseAccuracyTest {

    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png");
    private static final int POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 5000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Sample(String language, Path image, Path transcription) {
        String name() {
            String file = image.getFileName().toString();
            return file.substring(0, file.lastIndexOf('.'));
        }
    }

    private record Result(Sample sample, String hypothesis, double cer, double wer, int referenceChars,
                          int characterErrors, double digitiseSeconds, String textSource, String error) {
    }

    @Test
    void measureAccuracyOnTheLocalSamples() throws Exception {
        Path root = Path.of(System.getenv("CHITTHI_ACCURACY_DIR"));
        List<Sample> samples = findSamples(root);
        assertFalse(samples.isEmpty(), "No <language>/<name>.jpg|png with a matching .txt found under " + root);

        OcrProperties ocrProperties = loadOcrProperties();
        UsagePricing pricing = loadPricing();
        BigDecimal estimate = pricing.visionPerPage().multiply(BigDecimal.valueOf(samples.size()));
        System.out.printf("Submitting %d page(s) to Sarvam Digitise, estimated cost about Rs %s%n",
                samples.size(), estimate.toPlainString());

        SarvamClient client = buildRealSarvamClient();
        DigitiseResultParser parser = new DigitiseResultParser(
                new PageTextExtractor(ocrProperties), ocrProperties, objectMapper);

        List<Result> results = new ArrayList<>();
        for (Sample sample : samples) {
            Result result = evaluate(client, parser, sample);
            results.add(result);
            System.out.println(result.error() == null
                    ? "%s/%s CER %.3f WER %.3f".formatted(sample.language(), sample.name(), result.cer(), result.wer())
                    : "%s/%s FAILED: %s".formatted(sample.language(), sample.name(), result.error()));
        }

        String report = renderReport(results, estimate);
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("accuracy-report.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Report written to " + target.resolve("accuracy-report.md").toAbsolutePath());
    }

    private Result evaluate(SarvamClient client, DigitiseResultParser parser, Sample sample) {
        try {
            String reference = Files.readString(sample.transcription(), StandardCharsets.UTF_8);
            byte[] payload = zipOnePage(sample.image());

            long started = System.nanoTime();
            DigitiseJobResponse submitted = client.submitDigitiseJob(
                    payload, "accuracy-" + sample.name() + ".zip", sample.language());
            JobStatusResponse status = pollUntilDone(client, submitted.jobId());
            if (status.status() != JobStatus.COMPLETED && status.status() != JobStatus.PARTIALLY_COMPLETED) {
                return failed(sample, "job ended as " + status.status());
            }
            byte[] zip = client.downloadResult(client.getDownloadUrl(submitted.jobId()).downloadUrl());
            double seconds = Duration.ofNanos(System.nanoTime() - started).toMillis() / 1000.0;

            List<ParsedPage> pages = parser.parse(zip, new PageRange(1, 1));
            if (pages.isEmpty()) {
                return failed(sample, "no text recovered from the result ZIP");
            }
            String hypothesis = pages.get(0).text();
            saveOutput(sample, hypothesis);

            return new Result(sample, hypothesis,
                    CharacterErrorRate.cer(reference, hypothesis), CharacterErrorRate.wer(reference, hypothesis),
                    CharacterErrorRate.referenceLength(reference),
                    CharacterErrorRate.characterDistance(reference, hypothesis),
                    seconds, findTextSource(zip, hypothesis), null);
        } catch (Exception e) {
            return failed(sample, e.toString());
        }
    }

    private Result failed(Sample sample, String error) {
        return new Result(sample, "", Double.NaN, Double.NaN, 0, 0, Double.NaN, "-", error);
    }

    /** The first JSON key anywhere in the ZIP whose string value is exactly the text the parser returned. */
    private String findTextSource(byte[] zip, String text) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    continue;
                }
                String path = keyHolding(objectMapper.readTree(in.readAllBytes()), "", text);
                if (path != null) {
                    return entry.getName() + " -> " + path;
                }
            }
        }
        return "not a JSON field (per-page text file or split main document)";
    }

    private String keyHolding(JsonNode node, String path, String text) {
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isTextual() && value.asText().equals(text)) {
                return key;
            }
            if (value.isObject()) {
                String nested = keyHolding(value, key, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private void saveOutput(Sample sample, String hypothesis) throws IOException {
        Path dir = Path.of("target", "accuracy-output", sample.language());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(sample.name() + ".txt"), hypothesis, StandardCharsets.UTF_8);
    }

    private String renderReport(List<Result> results, BigDecimal estimate) {
        StringBuilder md = new StringBuilder();
        md.append("# Digitise handwriting accuracy\n\n");
        md.append("Error rates are Unicode code-point edit distance over the reference length, after NFC ")
                .append("normalisation and whitespace collapsing. In Indic scripts a missed vowel sign is one ")
                .append("error; this is not a grapheme-level figure. Estimated cost of this run: about Rs ")
                .append(estimate.toPlainString()).append(".\n\n");

        md.append("| Language | Image | CER | WER | Reference chars | Digitise time (s) | Text came from |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            if (r.error() != null) {
                md.append("| %s | %s | failed: %s | | | | |\n".formatted(
                        r.sample().language(), r.sample().name(), r.error().replace('|', '/')));
            } else {
                md.append("| %s | %s | %.3f | %.3f | %d | %.1f | %s |\n".formatted(
                        r.sample().language(), r.sample().name(), r.cer(), r.wer(),
                        r.referenceChars(), r.digitiseSeconds(), r.textSource()));
            }
        }

        md.append("\n## Aggregate (micro-averaged CER: total errors over total reference characters)\n\n");
        md.append("| Language | Images scored | Micro CER |\n|---|---|---|\n");
        Map<String, List<Result>> byLanguage = new TreeMap<>();
        results.stream().filter(r -> r.error() == null)
                .forEach(r -> byLanguage.computeIfAbsent(r.sample().language(), k -> new ArrayList<>()).add(r));
        byLanguage.forEach((language, rows) -> md.append("| %s | %d | %.3f |\n".formatted(
                language, rows.size(), microCer(rows))));
        List<Result> scored = results.stream().filter(r -> r.error() == null).toList();
        md.append("| all | %d | %.3f |\n".formatted(scored.size(), microCer(scored)));
        return md.toString();
    }

    private double microCer(List<Result> rows) {
        int errors = rows.stream().mapToInt(Result::characterErrors).sum();
        int chars = rows.stream().mapToInt(Result::referenceChars).sum();
        return chars == 0 ? Double.NaN : (double) errors / chars;
    }

    private List<Sample> findSamples(Path root) throws IOException {
        List<Sample> samples = new ArrayList<>();
        try (var languages = Files.list(root)) {
            for (Path languageDir : languages.filter(Files::isDirectory).sorted().toList()) {
                try (var files = Files.list(languageDir)) {
                    for (Path file : files.sorted().toList()) {
                        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (IMAGE_EXTENSIONS.stream().noneMatch(ext -> lower.endsWith("." + ext))) {
                            continue;
                        }
                        String base = file.getFileName().toString();
                        base = base.substring(0, base.lastIndexOf('.'));
                        Path transcription = languageDir.resolve(base + ".txt");
                        if (Files.isRegularFile(transcription)) {
                            samples.add(new Sample(languageDir.getFileName().toString(), file, transcription));
                        } else {
                            System.out.println("Skipping " + file + ": no " + base + ".txt beside it");
                        }
                    }
                }
            }
        }
        return samples;
    }

    /** Zipped exactly as the app's payload packager does: one page named page_001.<ext>. */
    private byte[] zipOnePage(Path image) throws IOException {
        String file = image.getFileName().toString();
        String extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("page_001." + extension));
            zip.write(Files.readAllBytes(image));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private JobStatusResponse pollUntilDone(SarvamClient client, String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            JobStatusResponse status = client.getJobStatus(jobId);
            if (status.status() != JobStatus.PENDING && status.status() != JobStatus.RUNNING) {
                return status;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new IllegalStateException("Digitise job " + jobId + " did not finish within "
                + POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000 + "s");
    }

    private SarvamClient buildRealSarvamClient() {
        SarvamProperties properties = new SarvamProperties(
                "https://api.sarvam.ai",
                System.getenv("SARVAM_API_KEY"),
                new SarvamProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(60)),
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
        return new SarvamClient(restClient, downloadRestClient, properties, new SarvamResilience(properties));
    }

    private Binder applicationYamlBinder() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources));
    }

    private OcrProperties loadOcrProperties() throws IOException {
        return applicationYamlBinder().bind("chitthi.ocr", OcrProperties.class).get();
    }

    private UsagePricing loadPricing() throws IOException {
        return applicationYamlBinder().bind("chitthi.usage.pricing", UsagePricing.class).get();
    }
}
