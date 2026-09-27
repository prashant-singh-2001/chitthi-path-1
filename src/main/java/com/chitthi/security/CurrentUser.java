package com.chitthi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * The one place that reads "who is making this request" out of Spring
 * Security's context - every controller that needs an owner id goes through
 * here rather than reading {@link SecurityContextHolder} itself, so there is
 * exactly one spot that knows a Google login's identity is its OIDC
 * {@code sub} claim, not its display name (which can change) or email
 * (which Google lets a user change too).
 *
 * <p>{@code /api/**} requires authentication (see {@link SecurityConfig}),
 * so every controller method this is injected into can only ever be
 * reached with an authenticated request - {@link #ownerId()} throwing on an
 * unauthenticated context is a programming-error guard, not a path a real
 * request takes.
 */
@Component
public class CurrentUser {

    public String ownerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user in this request");
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser.getSubject();
        }
        // DevUserAuthenticationFilter's principal is the dev-user (or
        // X-User-Id-overridden) name itself, as a plain String.
        return authentication.getName();
    }
}
