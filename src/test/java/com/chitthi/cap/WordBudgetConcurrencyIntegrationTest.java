package com.chitthi.cap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR15's acceptance test for the claim itself: however many callers race to
 * spend the same budget at once, the total ever granted never exceeds the
 * cap, and the stored ledger row always matches what was actually granted -
 * the same "0 duplicate paid calls" property Day 8-9's {@code stage_task}
 * claim proved for idempotency, here proved for a shared budget instead.
 */
@Testcontainers
@SpringBootTest(properties = {
        "chitthi.ocr.worker.enabled=false",
        "chitthi.ocr.poller.enabled=false",
        "chitthi.translate.worker.enabled=false",
        "chitthi.tts.worker.enabled=false",
        "chitthi.assemble.worker.enabled=false",
        "chitthi.cap.daily-words=1000"
})
class WordBudgetConcurrencyIntegrationTest {

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

    @Autowired
    WordBudgetService budgetService;

    @Autowired
    UserDailyUsageRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    WordCapProperties properties;

    /**
     * 50 threads each try to claim 100 words against a 1,000-word cap - only
     * 10 can ever win. {@link WordBudgetService#tryClaim} requires an open
     * transaction (see its Javadoc), so each attempt runs in its own,
     * exactly as a real caller (e.g. {@code OcrResultApplier}) would.
     */
    @Test
    void concurrentClaimsNeverOverspendTheBudget() throws Exception {
        String owner = "cap-race-owner";
        int threads = 50;
        int wordsPerClaim = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        AtomicInteger granted = new AtomicInteger();

        try {
            List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> transactionTemplate.execute(status -> {
                        boolean allowed = budgetService.tryClaim(owner, wordsPerClaim);
                        if (allowed) {
                            granted.incrementAndGet();
                        }
                        return allowed;
                    }))
                    .toList();

            List<Future<Boolean>> futures = pool.invokeAll(tasks);
            for (Future<Boolean> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(granted.get()).isEqualTo(10);

        LocalDate today = LocalDate.now(ZoneId.of(properties.zone()));
        UserDailyUsage row = repository.findByOwnerIdAndDay(owner, today).orElseThrow();
        assertThat(row.getWords()).isEqualTo(granted.get() * wordsPerClaim);
    }
}
