package com.chitthi.usage.web;

import com.chitthi.security.CurrentUser;
import com.chitthi.usage.UsageQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/** {@code GET /api/usage}: the requirements doc's spend-and-latency summary endpoint. */
@RestController
public class UsageController {

    private final UsageQueryService usageQueryService;
    private final CurrentUser currentUser;

    public UsageController(UsageQueryService usageQueryService, CurrentUser currentUser) {
        this.usageQueryService = usageQueryService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/usage")
    public UsageSummaryView usage(@RequestParam(required = false) OffsetDateTime from,
                                   @RequestParam(required = false) OffsetDateTime to) {
        OffsetDateTime effectiveTo = to != null ? to : OffsetDateTime.now();
        OffsetDateTime effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        return usageQueryService.ownerUsage(currentUser.ownerId(), effectiveFrom, effectiveTo);
    }
}
