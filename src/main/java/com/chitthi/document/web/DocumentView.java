package com.chitthi.document.web;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentView(UUID id, String title, String language, String status,
                            Integer year, List<String> tags, OffsetDateTime createdAt,
                            List<PageView> pages) {

    public static DocumentView from(Document document, List<Page> pages) {
        return new DocumentView(
                document.getId(),
                document.getTitle(),
                document.getLanguage(),
                document.getStatus().name(),
                document.getYear(),
                document.getTags(),
                document.getCreatedAt(),
                pages.stream().map(PageView::from).toList());
    }
}
