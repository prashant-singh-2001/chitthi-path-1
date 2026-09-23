package com.chitthi.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pipeline's message broker topology. One topic exchange carries every
 * stage's queue (only OCR exists so far; translate and TTS join it on
 * Day 5-6), backed by one dead-letter exchange so a poison message always has
 * somewhere durable to land instead of being silently dropped or requeued
 * forever.
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public TopicExchange pipelineExchange() {
        return new TopicExchange(PipelineQueues.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(PipelineQueues.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue ocrQueue() {
        return QueueBuilder.durable(PipelineQueues.OCR_QUEUE)
                .deadLetterExchange(PipelineQueues.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(PipelineQueues.OCR_DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue ocrDeadLetterQueue() {
        return QueueBuilder.durable(PipelineQueues.OCR_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding ocrBinding(Queue ocrQueue, TopicExchange pipelineExchange) {
        return BindingBuilder.bind(ocrQueue).to(pipelineExchange).with(PipelineQueues.OCR_QUEUE);
    }

    @Bean
    public Binding ocrDeadLetterBinding(Queue ocrDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(ocrDeadLetterQueue).to(deadLetterExchange)
                .with(PipelineQueues.OCR_DEAD_LETTER_QUEUE);
    }

    /**
     * Built on Boot's auto-configured {@link ObjectMapper} (not a fresh one)
     * so {@code ParameterNamesModule} is registered and record payloads
     * deserialize correctly. Type precedence is INFERRED rather than the
     * default (header-first): the listener method's declared parameter type
     * wins over the message's {@code __TypeId__} header, so moving or
     * renaming a payload record can never strand a message already queued
     * with the old header value.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.chitthi.*");
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /**
     * {@code mandatory(true)} plus a returns callback logging at ERROR: an
     * unroutable publish (a typo'd routing key, a queue not yet declared)
     * would otherwise vanish silently, which for OCR work means a page stays
     * PENDING forever with nothing in any log explaining why.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> org.slf4j.LoggerFactory.getLogger(RabbitMqConfig.class)
                .error("Message returned as unroutable: exchange={}, routingKey={}, replyCode={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
        return template;
    }

    /**
     * Overrides Boot's default listener container factory. Prefetch of 1
     * favors fairness over throughput - an OCR batch submit is slow (a
     * multi-megabyte ZIP upload) and expensive (a paid, rate-limited call),
     * so one consumer should not hoard several while others sit idle.
     * {@code defaultRequeueRejected(false)} ensures a poisoned message
     * reaches the dead-letter queue after retries are exhausted instead of
     * looping forever.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${chitthi.ocr.worker.concurrency:2}") int concurrency,
            @Value("${chitthi.ocr.worker.max-concurrency:4}") int maxConcurrency) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(maxConcurrency);
        return factory;
    }
}
