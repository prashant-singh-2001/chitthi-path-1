package com.chitthi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates every request as a fixed dev user, only ever registered
 * (see {@link SecurityConfig}) when {@code chitthi.security.dev-user} is
 * set - which production must never do. With the property unset, this class
 * is never instantiated, so an {@code X-User-Id} header is never trusted for
 * anything, closing what used to be a complete authentication bypass.
 *
 * <p>Honors an {@code X-User-Id} header as an override of the configured dev
 * user, so a single test run can act as several different identities - see
 * {@code SearchIntegrationTest}'s two-owner assertions and
 * {@code DocumentOwnershipIntegrationTest}. That header is meaningful only
 * because this filter exists at all: it still cannot do anything unless
 * dev-user is configured in the first place.
 */
public class DevUserAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DevUserAuthenticationFilter.class);

    private final String devUser;

    public DevUserAuthenticationFilter(String devUser) {
        this.devUser = devUser;
        log.warn("chitthi.security.dev-user is set ('{}') - every request is trusted as this user (or as whatever "
                + "X-User-Id header it carries) with no real authentication. This must never be set in production.",
                devUser);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String headerOverride = request.getHeader("X-User-Id");
        String effectiveUser = (headerOverride != null && !headerOverride.isBlank()) ? headerOverride : devUser;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(effectiveUser, null, List.of()));
        chain.doFilter(request, response);
    }
}
