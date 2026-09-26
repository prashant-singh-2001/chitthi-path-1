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

    /** Every queue a message can be retried back onto - see {@link RabbitMqConfig#retryQueues}. */
    public static final java.util.List<String> RETRYABLE_QUEUES =
            java.util.List.of(OCR_QUEUE, TRANSLATE_QUEUE, TTS_QUEUE, ASSEMBLE_QUEUE);

    /**
     * One queue per (destination, delay) pair, published to directly via the
     * default exchange (routing key = queue name, the implicit binding every
     * queue has to the default exchange) and declared with an explicit
     * {@code x-dead-letter-routing-key} of {@code destinationQueue} - so
     * there is no reliance on whichever routing key RabbitMQ happens to
     * preserve through dead-lettering, only an argument that always wins.
     */
    public static String retryQueueName(String destinationQueue, java.time.Duration delay) {
        return "chitthi.retry.%s.%dms".formatted(destinationQueue, delay.toMillis());
    }

    private PipelineQueues() {
    }
}
