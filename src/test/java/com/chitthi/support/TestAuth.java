package com.chitthi.support;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Every integration test authenticates via {@code chitthi.security.dev-user}
 * (see {@code DevUserAuthenticationFilter}), which sidesteps Google entirely
 * - but CSRF protection is independent of how a request got authenticated,
 * so a POST/PUT still needs a valid token even in dev mode. {@link TestRestTemplate}
 * has no cookie jar of its own, so this fetches the CSRF cookie with one GET
 * and replays it as both the {@code Cookie} and {@code X-XSRF-TOKEN} headers
 * a real browser's fetch would send automatically.
 */
public final class TestAuth {

    private TestAuth() {
    }

    /** Adds CSRF headers to an existing {@link HttpHeaders} - use when a test already builds its own (e.g. a multipart upload). */
    public static void addCsrf(HttpHeaders headers, TestRestTemplate restTemplate) {
        String token = fetchCsrfToken(restTemplate);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
    }

    /** A ready-to-send JSON {@link HttpEntity} with a CSRF token attached - for a PUT/POST whose body is just a small JSON object. */
    public static <T> HttpEntity<T> jsonWithCsrf(T body, TestRestTemplate restTemplate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        addCsrf(headers, restTemplate);
        return new HttpEntity<>(body, headers);
    }

    /** A ready-to-send empty-body {@link HttpEntity} with a CSRF token attached - for a POST that takes no body, like the retry endpoint. */
    public static HttpEntity<Void> emptyWithCsrf(TestRestTemplate restTemplate) {
        HttpHeaders headers = new HttpHeaders();
        addCsrf(headers, restTemplate);
        return new HttpEntity<>(null, headers);
    }

    private static String fetchCsrfToken(TestRestTemplate restTemplate) {
        ResponseEntity<String> probe = restTemplate.getForEntity("/actuator/health", String.class);
        List<String> setCookieHeaders = probe.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookieHeaders == null) {
            throw new IllegalStateException("No Set-Cookie header in the CSRF probe response");
        }
        for (String header : setCookieHeaders) {
            if (header.startsWith("XSRF-TOKEN=")) {
                return header.substring("XSRF-TOKEN=".length()).split(";", 2)[0];
            }
        }
        throw new IllegalStateException("No XSRF-TOKEN cookie found in: " + setCookieHeaders);
    }
}
