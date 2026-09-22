package com.chitthi.document.service;

/** One page's rendered image, 1-indexed, ready to persist to object storage. */
public record PageImage(int pageNo, byte[] content, String contentType) {
}
