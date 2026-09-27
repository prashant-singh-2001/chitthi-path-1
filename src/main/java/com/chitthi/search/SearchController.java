package com.chitthi.search;

import com.chitthi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/search}: FR9's full-text search across original and translated page text. */
@RestController
public class SearchController {

    private final SearchService searchService;
    private final CurrentUser currentUser;

    public SearchController(SearchService searchService, CurrentUser currentUser) {
        this.searchService = searchService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam String q,
                                  @RequestParam(required = false) String tag,
                                  @RequestParam(required = false) Integer year,
                                  @RequestParam(required = false) Integer limit) {
        return searchService.search(currentUser.ownerId(), q, tag, year, limit);
    }
}
