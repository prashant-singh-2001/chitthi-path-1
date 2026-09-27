package com.chitthi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesTheDevUserFilterAsPrincipalName() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dev-user-42", null, List.of()));

        assertThat(currentUser.ownerId()).isEqualTo("dev-user-42");
    }

    @Test
    void resolvesAGoogleLoginBySubjectNotByDisplayNameOrEmail() {
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "google-sub-123", "name", "A Display Name", "email", "someone@example.com"));
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(oidcUser, null, List.of()));

        assertThat(currentUser.ownerId()).isEqualTo("google-sub-123");
    }

    @Test
    void throwsWhenNothingIsAuthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(currentUser::ownerId).isInstanceOf(IllegalStateException.class);
    }
}
