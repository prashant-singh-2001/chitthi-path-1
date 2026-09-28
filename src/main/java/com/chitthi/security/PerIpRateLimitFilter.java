package com.chitthi.security;

import com.chitthi.document.web.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FR15's per-IP request limit on {@code /api/**}, {@code /oauth2/**} and
 * {@code /login/**} - the SSE progress stream and every {@code /actuator/**}
 * path are excluded (see {@link #shouldNotFilter}). Deliberately not built on
 * the {@code RateLimiterRegistry} bean {@code resilience4j-spring-boot3}
 * autoconfigures: that registry never evicts entries, and its metrics
 * autoconfiguration would export one {@code resilience4j_ratelimiter_*}
 * series per key - keyed by client IP, that is unbounded Prometheus label
 * cardinality anyone rotating source addresses could turn into a memory
 * problem. Limiters are instead built by hand, exactly as
 * {@link com.chitthi.sarvam.SarvamResilience} already does for Sarvam calls,
 * and held in a small bounded, evicting map of our own.
 *
 * <p>Two deliberate departures from {@code SarvamResilience}'s shape, both
 * because this guards our own request rate rather than a shared outbound
 * quota: {@code limitForPeriod} is the whole per-minute budget at once (not
 * one trickling permit) since the SPA legitimately fires several requests
 * together on page load and a trickle would reject most of them; and
 * {@code timeoutDuration} is {@link Duration#ZERO} (not a multi-second wait)
 * since a filter must reject immediately rather than park a request thread.
 *
 * <p>In memory, single instance - the same shape of gap as
 * {@code SarvamResilience}'s and {@code SseEmitterRegistry}'s TODO(scale):
 * several app instances would each hold a separate per-IP budget.
 *
 * <p>Reads only {@link HttpServletRequest#getRemoteAddr()} - never
 * {@code X-Forwarded-For}, which any client can set. Deployment behind a
 * proxy is Spring Boot's standard {@code server.forward-headers-strategy},
 * not something this filter parses itself.
 */
public class PerIpRateLimitFilter extends OncePerRequestFilter {

    /** Loose enough to never be the actual constraint - large real-world deployments still fit comfortably. */
    private static final int MAX_TRACKED_IPS = 10_000;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String[] LIMITED_PATTERNS = {"/api/**", "/oauth2/**", "/login/**"};
    private static final String[] EXCLUDED_PATTERNS = {"/api/documents/*/events", "/actuator/**"};

    private final RatelimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, RateLimiter> limitersByIp;

    public PerIpRateLimitFilter(RatelimitProperties properties, MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        // Access-ordered so the eldest (least-recently-used) entry is evicted
        // first - an actively flooding address is the last thing evicted,
        // not the first, since every request it makes re-promotes its entry.
        this.limitersByIp = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RateLimiter> eldest) {
                return size() > MAX_TRACKED_IPS;
            }
        });
        Gauge.builder("chitthi.ratelimit.tracked.ips", limitersByIp, Map::size)
                .description("Number of client IPs currently holding a rate limiter")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean limited = matchesAny(path, LIMITED_PATTERNS);
        boolean excluded = matchesAny(path, EXCLUDED_PATTERNS);
        return !limited || excluded;
    }

    private boolean matchesAny(String path, String[] patterns) {
        for (String pattern : patterns) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimiter limiter = limiterFor(request.getRemoteAddr());
        boolean permitted = limiter.acquirePermission();
        meterRegistry.counter("chitthi.ratelimit.requests", "outcome", permitted ? "allowed" : "rejected").increment();

        if (!permitted) {
            writeRejection(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private RateLimiter limiterFor(String ip) {
        return limitersByIp.computeIfAbsent(ip, key -> RateLimiter.of(key, rateLimiterConfig()));
    }

    private RateLimiterConfig rateLimiterConfig() {
        return RateLimiterConfig.custom()
                .limitForPeriod(properties.perIpRequestsPerMinute())
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    private void writeRejection(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse("Too many requests from this address; try again in a minute"));
    }
}
