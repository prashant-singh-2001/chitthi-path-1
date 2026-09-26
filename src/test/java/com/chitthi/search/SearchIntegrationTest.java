package com.chitthi.search;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR9's acceptance criterion: search runs under 300ms. Seeds 1,000 pages
 * directly through the repositories - the pipeline itself is Day 3-9's
 * concern, not this test's - with every worker disabled so the seeded rows
 * are never picked up as work.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.worker.enabled=false",
        "chitthi.ocr.poller.enabled=false",
        "chitthi.translate.worker.enabled=false",
        "chitthi.tts.worker.enabled=false",
        "chitthi.assemble.worker.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIntegrationTest {

    private static final String OWNER = "search-owner";
    private static final String OTHER_OWNER = "other-owner";
    private static final int DOCUMENT_COUNT = 40;
    private static final int PAGES_PER_DOCUMENT = 25;
    private static final int NEEDLE_DOCUMENT_INDEX = 0;
    private static final int OTHER_OWNER_FROM_INDEX = 35;
    private static final String ENGLISH_NEEDLE = "unobtainium";
    private static final String DEVANAGARI_NEEDLE = "अद्वितीयशब्द";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    PageRepository pageRepository;

    private final List<Document> documents = new ArrayList<>();
    private final Map<UUID, Document> documentsById = new HashMap<>();

    @BeforeAll
    void seedOneThousandPages() {
        for (int d = 0; d < DOCUMENT_COUNT; d++) {
            String owner = d < OTHER_OWNER_FROM_INDEX ? OWNER : OTHER_OWNER;
            List<String> tags = d % 2 == 0 ? List.of("family") : List.of("work");
            Integer year = 1980 + (d % 10);
            documents.add(new Document(owner, "Letter " + d, "hi", tags, year));
        }
        documentRepository.saveAll(documents);
        for (int d = 0; d < documents.size(); d++) {
            documentsById.put(documents.get(d).getId(), documents.get(d));
        }

        List<Page> pages = new ArrayList<>();
        for (int d = 0; d < documents.size(); d++) {
            Document document = documents.get(d);
            for (int p = 1; p <= PAGES_PER_DOCUMENT; p++) {
                Page page = new Page(document.getId(), p, "key-%d-%d".formatted(d, p));
                page.setStatus(PageStatus.INDEXED);
                page.setOriginalText(devanagariFillerText(d, p));
                page.setTranslatedText(englishFillerText(d, p));
                page.setTextHash("hash-%d-%d".formatted(d, p));
                pages.add(page);
            }
        }
        pageRepository.saveAll(pages);
    }

    private String englishFillerText(int documentIndex, int pageNo) {
        String needle = documentIndex == NEEDLE_DOCUMENT_INDEX && pageNo == 1 ? " " + ENGLISH_NEEDLE : "";
        return "This is a translated letter, page %d of document %d.%s".formatted(pageNo, documentIndex, needle);
    }

    private String devanagariFillerText(int documentIndex, int pageNo) {
        String needle = documentIndex == NEEDLE_DOCUMENT_INDEX && pageNo == 2 ? " " + DEVANAGARI_NEEDLE : "";
        return "यह पत्र का पृष्ठ है।" + needle;
    }

    @Test
    void anEnglishWordFindsTheTranslatedMatch() {
        SearchResponse response = search(OWNER, ENGLISH_NEEDLE, null, null);

        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).documentId()).isEqualTo(documents.get(NEEDLE_DOCUMENT_INDEX).getId());
        assertThat(response.hits().get(0).pageNo()).isEqualTo(1);
        assertThat(response.hits().get(0).matchedIn()).isEqualTo("TRANSLATED");
    }

    @Test
    void aDevanagariSubstringFindsTheOriginalMatchThroughTheTrigramIndex() {
        SearchResponse response = search(OWNER, DEVANAGARI_NEEDLE, null, null);

        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).documentId()).isEqualTo(documents.get(NEEDLE_DOCUMENT_INDEX).getId());
        assertThat(response.hits().get(0).pageNo()).isEqualTo(2);
        assertThat(response.hits().get(0).matchedIn()).isEqualTo("ORIGINAL");
    }

    @Test
    void anotherOwnersDocumentsNeverAppear() {
        SearchResponse response = search(OTHER_OWNER, ENGLISH_NEEDLE, null, null);

        assertThat(response.hits()).isEmpty();
    }

    @Test
    void theTagFilterNarrowsResults() {
        SearchResponse allFamily = search(OWNER, "letter", "family", null);
        SearchResponse allWork = search(OWNER, "letter", "work", null);

        assertThat(allFamily.hits()).isNotEmpty();
        assertThat(allWork.hits()).isNotEmpty();
        for (SearchHit hit : allFamily.hits()) {
            assertThat(documentsById.get(hit.documentId()).getTags()).containsExactly("family");
        }
        for (SearchHit hit : allWork.hits()) {
            assertThat(documentsById.get(hit.documentId()).getTags()).containsExactly("work");
        }
    }

    @Test
    void theYearFilterNarrowsResults() {
        SearchResponse response = search(OWNER, "letter", null, 1980);

        assertThat(response.hits()).isNotEmpty();
        for (SearchHit hit : response.hits()) {
            assertThat(hit.year()).isEqualTo(1980);
        }
    }

    @Test
    void searchStaysUnderThreeHundredMillisecondsAtP95() {
        List<Long> latenciesMs = new ArrayList<>();
        // One warm-up call outside the measured set, so a cold query plan
        // doesn't skew the very first measurement.
        search(OWNER, "letter", null, null);

        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            search(OWNER, "letter", null, null);
            latenciesMs.add((System.nanoTime() - start) / 1_000_000);
        }

        latenciesMs.sort(Long::compareTo);
        long p95 = latenciesMs.get((int) Math.ceil(latenciesMs.size() * 0.95) - 1);
        assertThat(p95).as("p95 latency over %s runs: %s", latenciesMs.size(), latenciesMs).isLessThan(300);
    }

    private SearchResponse search(String owner, String q, String tag, Integer year) {
        StringBuilder url = new StringBuilder("http://localhost:%d/api/search?q=%s".formatted(port, q));
        if (tag != null) {
            url.append("&tag=").append(tag);
        }
        if (year != null) {
            url.append("&year=").append(year);
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-User-Id", owner);
        ResponseEntity<SearchResponse> response = restTemplate.exchange(
                url.toString(), org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), SearchResponse.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }
}
