package com.chitthi.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/search}: FR9's full-text search across original and translated page text. */
@RestController
public class SearchController {

    // TODO(FR14): replace with the authenticated principal once Google
    // OAuth sign-in lands - matches DocumentController's DEFAULT_OWNER_ID
    // stopgap until then.
    private static final String DEFAULT_OWNER_ID = "demo-user";

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam String q,
                                  @RequestParam(required = false) String tag,
                                  @RequestParam(required = false) Integer year,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = userId != null ? userId : DEFAULT_OWNER_ID;
        return searchService.search(owner, q, tag, year, limit);
    }
}
