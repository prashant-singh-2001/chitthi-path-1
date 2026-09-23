package com.chitthi.ocr.service;

public class OcrPayloadPackagingException extends RuntimeException {

    public OcrPayloadPackagingException(String message) {
        super(message);
    }

    public OcrPayloadPackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
