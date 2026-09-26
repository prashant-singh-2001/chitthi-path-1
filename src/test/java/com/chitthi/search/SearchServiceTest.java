package com.chitthi.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private final SearchRepository repository = mock(SearchRepository.class);
    private final SearchService service = new SearchService(repository);

    @Test
    void aQueryShorterThanTwoCharactersIsRejected() {
        assertThatThrownBy(() -> service.search("owner", "a", null, null, null))
                .isInstanceOf(InvalidSearchQueryException.class);
    }

    @Test
    void aQueryLongerThan200CharactersIsRejected() {
        assertThatThrownBy(() -> service.search("owner", "x".repeat(201), null, null, null))
                .isInstanceOf(InvalidSearchQueryException.class);
    }

    @Test
    void aNullQueryIsRejected() {
        assertThatThrownBy(() -> service.search("owner", null, null, null, null))
                .isInstanceOf(InvalidSearchQueryException.class);
    }

    @Test
    void aLimitOver50IsRejected() {
        assertThatThrownBy(() -> service.search("owner", "letter", null, null, 51))
                .isInstanceOf(InvalidSearchQueryException.class);
    }

    @Test
    void aZeroOrNegativeLimitIsRejected() {
        assertThatThrownBy(() -> service.search("owner", "letter", null, null, 0))
                .isInstanceOf(InvalidSearchQueryException.class);
    }

    @Test
    void aMissingLimitDefaultsToTwenty() {
        when(repository.search(eq("owner"), eq("letter"), isNull(), isNull(), eq(20))).thenReturn(List.of());

        service.search("owner", "letter", null, null, null);

        verify(repository).search("owner", "letter", null, null, 20);
    }

    @Test
    void aValidQueryReturnsHitsAndAnElapsedTime() {
        SearchHit hit = new SearchHit(java.util.UUID.randomUUID(), "title", 1987, List.of("family"), 1, "snippet", "TRANSLATED", 0.5);
        when(repository.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of(hit));

        SearchResponse response = service.search("owner", "letter", "family", 1987, 10);

        assertThat(response.hits()).containsExactly(hit);
        assertThat(response.tookMs()).isGreaterThanOrEqualTo(0);
        verify(repository).search("owner", "letter", "family", 1987, 10);
    }
}
