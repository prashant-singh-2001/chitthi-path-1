package com.chitthi.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link PerIpRateLimitFilter} is actually wired into the real
 * security filter chain, not just that the filter class works in isolation
 * (see {@code PerIpRateLimitFilterTest} for that). {@code TestRestTemplate}
 * always arrives from {@code 127.0.0.1}, so this can only prove one client's
 * behavior, not per-IP isolation - that's the unit test's job.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.worker.enabled=false",
        "chitthi.ocr.poller.enabled=false",
        "chitthi.translate.worker.enabled=false",
        "chitthi.tts.worker.enabled=false",
        "chitthi.assemble.worker.enabled=false",
        "chitthi.security.dev-user=ratelimit-test-user",
        "chitthi.ratelimit.per-ip-requests-per-minute=3"
})
class PerIpRateLimitIntegrationTest {

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
    TestRestTemplate restTemplate;

    @Test
    void exceedingThePerIpLimitOnApiPathsReturns429() {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = restTemplate.getForEntity("/api/documents/" + UUID.randomUUID(), String.class);
            // A dev-user-authenticated request for a random id is 404, not
            // 401/429 - it reached the app and was correctly handled.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        ResponseEntity<String> limited = restTemplate.getForEntity("/api/documents/" + UUID.randomUUID(), String.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(limited.getBody()).contains("Too many requests");
    }

    @Test
    void actuatorHealthIsNeverRateLimited() {
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
