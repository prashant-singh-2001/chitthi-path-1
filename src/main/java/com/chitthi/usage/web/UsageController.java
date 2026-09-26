package com.chitthi.usage.web;

import com.chitthi.usage.UsageQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/** {@code GET /api/usage}: the requirements doc's spend-and-latency summary endpoint. */
@RestController
public class UsageController {

    // TODO(FR14): replace with the authenticated principal once Google
    // OAuth sign-in lands - matches DocumentController's DEFAULT_OWNER_ID
    // stopgap until then.
    private static final String DEFAULT_OWNER_ID = "demo-user";

    private final UsageQueryService usageQueryService;

    public UsageController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/api/usage")
    public UsageSummaryView usage(@RequestParam(required = false) OffsetDateTime from,
                                   @RequestParam(required = false) OffsetDateTime to,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = userId != null ? userId : DEFAULT_OWNER_ID;
        OffsetDateTime effectiveTo = to != null ? to : OffsetDateTime.now();
        OffsetDateTime effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        return usageQueryService.ownerUsage(owner, effectiveFrom, effectiveTo);
    }
}
