package com.chitthi.ocr.service;

/**
 * A packaged batch ready to submit to Sarvam. {@code filename} carries the
 * document id and page range ({@code "{documentId}_{pageRange}.zip"}) so a
 * WireMock stub - or a log line - can tell which submitted archive belongs to
 * which chunk without depending on submission order.
 */
public record OcrPayload(byte[] content, String filename) {
}
