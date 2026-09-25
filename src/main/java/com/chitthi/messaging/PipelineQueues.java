package com.chitthi.messaging;

/**
 * Topology constants shared between publishers and listeners, keeping the
 * exchange/DLX wiring in {@link RabbitMqConfig} written once per queue.
 */
public final class PipelineQueues {

    public static final String EXCHANGE = "chitthi.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "chitthi.dlx";

    public static final String OCR_QUEUE = "ocr.queue";
    public static final String OCR_DEAD_LETTER_QUEUE = "ocr.queue.dlq";

    public static final String TRANSLATE_QUEUE = "translate.queue";
    public static final String TRANSLATE_DEAD_LETTER_QUEUE = "translate.queue.dlq";

    public static final String TTS_QUEUE = "tts.queue";
    public static final String TTS_DEAD_LETTER_QUEUE = "tts.queue.dlq";

    public static final String ASSEMBLE_QUEUE = "assemble.queue";
    public static final String ASSEMBLE_DEAD_LETTER_QUEUE = "assemble.queue.dlq";

    private PipelineQueues() {
    }
}
