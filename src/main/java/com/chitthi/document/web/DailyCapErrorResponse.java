package com.chitthi.document.web;

import java.time.OffsetDateTime;

/** FR15: a 429 body carrying enough for the frontend to say when the budget frees up, not just that it's spent. */
public record DailyCapErrorResponse(String message, OffsetDateTime resetAt, int usedWords, int limitWords) {
}
