package com.chitthi.document.service;

import java.util.UUID;

public class PageNotFoundException extends RuntimeException {

    public PageNotFoundException(UUID documentId, int pageNo) {
        super("Page %d not found for document %s".formatted(pageNo, documentId));
    }
}
