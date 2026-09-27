package com.chitthi.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * FR14 with neither {@code chitthi.security.dev-user} nor Google credentials
 * configured - the production-shaped case, and the regression test for the
 * bypass this PR closes: {@code X-User-Id} used to be trusted unconditionally
 * with no authentication at all.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chitthi.ocr.worker.enabled=false",
        "chitthi.ocr.poller.enabled=false",
        "chitthi.translate.worker.enabled=false",
        "chitthi.tts.worker.enabled=false",
        "chitthi.assemble.worker.enabled=false"
        // Deliberately no chitthi.security.dev-user and no Google client
        // id/secret - this is what a real deployment looks like before
        // anyone signs in.
})
class SecurityConfigTest {

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
    void anUnauthenticatedApiCallIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/documents/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorHealthStaysPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aForgedXUserIdHeaderIsIgnoredWithNoDevUserConfigured() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "attacker");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/documents/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
