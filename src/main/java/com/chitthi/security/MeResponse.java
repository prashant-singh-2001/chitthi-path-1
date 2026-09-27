package com.chitthi.security;

/** {@code GET /api/me}: the SPA's "am I signed in, and as whom" probe. {@code displayName}/{@code email} are null in dev mode. */
public record MeResponse(String ownerId, String displayName, String email) {
}
