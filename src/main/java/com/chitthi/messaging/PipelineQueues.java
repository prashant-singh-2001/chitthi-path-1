package com.chitthi.messaging;

/**
 * Topology constants shared between publishers and listeners. Translate and
 * TTS queues join this class on Day 5-6 alongside the OCR queue declared
 * here; keeping them in one place means the exchange/DLX wiring in
 * {@link RabbitMqConfig} only has to be written once.
 */
public final class PipelineQueues {

    public static final String EXCHANGE = "chitthi.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "chitthi.dlx";

    public static final String OCR_QUEUE = "ocr.queue";
    public static final String OCR_DEAD_LETTER_QUEUE = "ocr.queue.dlq";

    private PipelineQueues() {
    }
}
