package com.chitthi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Mockito here - {@link MockHttpServletRequest}/{@link MockHttpServletResponse}/{@link MockFilterChain} are
 * plain objects, so unlike most of this project's unit tests this one actually runs on this machine (JDK 25
 * can't mock concrete classes locally; CI on JDK 21 is otherwise the only verification). Real per-IP isolation
 * can only be asserted this way, too: {@code TestRestTemplate} always arrives from {@code 127.0.0.1}, so no
 * integration test can tell two clients apart.
 */
class PerIpRateLimitFilterTest {

    private PerIpRateLimitFilter newFilter(int perIpRequestsPerMinute) {
        return new PerIpRateLimitFilter(
                new RatelimitProperties(perIpRequestsPerMinute), new SimpleMeterRegistry(), new ObjectMapper());
    }

    private MockHttpServletRequest apiRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void allowsUpToTheConfiguredLimitThenRejects() throws Exception {
        PerIpRateLimitFilter filter = newFilter(3);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(apiRequest("10.0.0.1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(apiRequest("10.0.0.1"), rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("Too many requests");
    }

    @Test
    void oneAddressFloodingNeverAffectsAnother() throws Exception {
        PerIpRateLimitFilter filter = newFilter(1);

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(apiRequest("10.0.0.1"), first, new MockFilterChain());
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse floodedOut = new MockHttpServletResponse();
        filter.doFilter(apiRequest("10.0.0.1"), floodedOut, new MockFilterChain());
        assertThat(floodedOut.getStatus()).isEqualTo(429);

        // A completely different address still gets its own full budget.
        MockHttpServletResponse otherAddress = new MockHttpServletResponse();
        filter.doFilter(apiRequest("10.0.0.2"), otherAddress, new MockFilterChain());
        assertThat(otherAddress.getStatus()).isEqualTo(200);
    }

    @Test
    void theSseProgressStreamIsExcluded() throws Exception {
        PerIpRateLimitFilter filter = newFilter(1);
        MockHttpServletRequest sseRequest = new MockHttpServletRequest("GET",
                "/api/documents/123e4567-e89b-12d3-a456-426614174000/events");
        sseRequest.setRemoteAddr("10.0.0.1");

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(sseRequest, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void actuatorEndpointsAreExcluded() throws Exception {
        PerIpRateLimitFilter filter = newFilter(1);
        MockHttpServletRequest healthRequest = new MockHttpServletRequest("GET", "/actuator/health");
        healthRequest.setRemoteAddr("10.0.0.1");

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(healthRequest, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void oauthAndLoginPathsAreLimited() throws Exception {
        PerIpRateLimitFilter filter = newFilter(1);

        MockHttpServletRequest oauthRequest = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        oauthRequest.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(oauthRequest, first, new MockFilterChain());
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(oauthRequest, second, new MockFilterChain());
        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test
    void theBoundedMapEvictsRatherThanGrowingWithoutLimit() throws Exception {
        PerIpRateLimitFilter filter = newFilter(1);

        for (int i = 0; i < 10_050; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(apiRequest("10.0." + (i / 256) + "." + (i % 256)), response, new MockFilterChain());
        }

        @SuppressWarnings("unchecked")
        Map<String, ?> limiters = (Map<String, ?>) getField(filter, "limitersByIp");
        assertThat(limiters.size()).isLessThanOrEqualTo(10_000);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
