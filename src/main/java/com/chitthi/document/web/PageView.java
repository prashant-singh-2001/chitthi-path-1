package com.chitthi.document.web;

import com.chitthi.document.model.Page;

public record PageView(int pageNo, String status, boolean edited, String originalText, String translatedText) {

    public static PageView from(Page page) {
        return new PageView(page.getPageNo(), page.getStatus().name(), page.isEdited(),
                page.getOriginalText(), page.getTranslatedText());
    }
}
