package com.chitthi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final CurrentUser currentUser;

    public MeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me")
    public MeResponse me() {
        String ownerId = currentUser.ownerId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return new MeResponse(ownerId, oidcUser.getFullName(), oidcUser.getEmail());
        }
        return new MeResponse(ownerId, null, null);
    }
}
