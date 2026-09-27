package com.chitthi.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * FR14: {@code /api/**} requires authentication; every user sees only their
 * own documents (the actual scoping happens per-endpoint in
 * {@code DocumentController} and friends, via {@link CurrentUser}).
 *
 * <p>Google sign-in only activates when both {@code GOOGLE_CLIENT_ID} and
 * {@code GOOGLE_CLIENT_SECRET} env vars are set - see {@link
 * #clientRegistrationRepository} for why those are read directly rather than
 * bound as a {@code spring.security.oauth2.client.registration.google.*}
 * property. Checked here via {@link ClientRegistrationRepository}'s
 * availability rather than assumed, so a local run with no Google
 * credentials still starts instead of failing on a missing bean. {@link
 * DevUserAuthenticationFilter} is the other way in, gated on {@code
 * chitthi.security.dev-user} and never registered without it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${chitthi.security.dev-user:}")
    private String devUser;

    /**
     * Deliberately not a {@code spring.security.oauth2.client.registration
     * .google.*} YAML property: binding {@code client-id: ${GOOGLE_CLIENT_ID:}}
     * would still create a "google" registration entry with an empty
     * client id when the env var is unset, and {@code ClientRegistration}'s
     * own builder rejects that at startup - breaking every local run and
     * every test. Reading the two env vars directly and returning {@code
     * null} when either is missing means no registration - and no bean -
     * exists at all, which {@link #filterChain}'s {@code ObjectProvider}
     * check treats the same as "Google isn't configured".
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(Environment environment) {
        String clientId = environment.getProperty("GOOGLE_CLIENT_ID");
        String clientSecret = environment.getProperty("GOOGLE_CLIENT_SECRET");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return null;
        }
        // Google's own long-standing, documented OIDC endpoints - the same
        // ones Spring Security's now-removed CommonOAuth2Provider.GOOGLE
        // used to fill in automatically.
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri("https://accounts.google.com")
                .clientName("Google")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> clientRegistrations)
            throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Spring Security 6's CSRF-BREACH-protection default only writes the
        // cookie lazily, as a request attribute, unless the request
        // attribute name is cleared - without this, the SPA never actually
        // receives a token to read back.
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/**", "/login/**", "/actuator/health").permitAll()
                        // TODO(deploy): restrict network access to
                        // /actuator/prometheus once deployed - it exposes
                        // per-document spend and must not be reachable from
                        // the public internet, not merely "unauthenticated
                        // is fine because who'd guess the path".
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .logout(logout -> logout.logoutUrl("/logout"))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        // /api/** is the only authenticated matcher above, so
                        // this only ever fires for an unauthenticated API
                        // call - a plain 401 lets the SPA show a sign-in
                        // prompt instead of Spring Security's default
                        // redirect-to-Google landing on a JSON fetch.
                        (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)));

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> {
            });
        }

        if (!devUser.isBlank()) {
            http.addFilterBefore(new DevUserAuthenticationFilter(devUser), UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
