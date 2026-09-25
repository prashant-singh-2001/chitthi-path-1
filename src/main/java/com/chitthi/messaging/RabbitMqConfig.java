package com.chitthi.messaging;

import com.chitthi.ocr.OcrProperties;
import com.chitthi.sarvam.SarvamProperties;
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
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pipeline's message broker topology. One topic exchange carries every
 * stage's queue, backed by one dead-letter exchange so a poison message
 * always has somewhere durable to land instead of being silently dropped or
 * requeued forever.
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

    @Bean
    public Queue translateQueue() {
        return QueueBuilder.durable(PipelineQueues.TRANSLATE_QUEUE)
                .deadLetterExchange(PipelineQueues.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(PipelineQueues.TRANSLATE_DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue translateDeadLetterQueue() {
        return QueueBuilder.durable(PipelineQueues.TRANSLATE_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding translateBinding(Queue translateQueue, TopicExchange pipelineExchange) {
        return BindingBuilder.bind(translateQueue).to(pipelineExchange).with(PipelineQueues.TRANSLATE_QUEUE);
    }

    @Bean
    public Binding translateDeadLetterBinding(Queue translateDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(translateDeadLetterQueue).to(deadLetterExchange)
                .with(PipelineQueues.TRANSLATE_DEAD_LETTER_QUEUE);
    }

    @Bean
    public Queue ttsQueue() {
        return QueueBuilder.durable(PipelineQueues.TTS_QUEUE)
                .deadLetterExchange(PipelineQueues.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(PipelineQueues.TTS_DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue ttsDeadLetterQueue() {
        return QueueBuilder.durable(PipelineQueues.TTS_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding ttsBinding(Queue ttsQueue, TopicExchange pipelineExchange) {
        return BindingBuilder.bind(ttsQueue).to(pipelineExchange).with(PipelineQueues.TTS_QUEUE);
    }

    @Bean
    public Binding ttsDeadLetterBinding(Queue ttsDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(ttsDeadLetterQueue).to(deadLetterExchange)
                .with(PipelineQueues.TTS_DEAD_LETTER_QUEUE);
    }

    @Bean
    public Queue assembleQueue() {
        return QueueBuilder.durable(PipelineQueues.ASSEMBLE_QUEUE)
                .deadLetterExchange(PipelineQueues.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(PipelineQueues.ASSEMBLE_DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue assembleDeadLetterQueue() {
        return QueueBuilder.durable(PipelineQueues.ASSEMBLE_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding assembleBinding(Queue assembleQueue, TopicExchange pipelineExchange) {
        return BindingBuilder.bind(assembleQueue).to(pipelineExchange).with(PipelineQueues.ASSEMBLE_QUEUE);
    }

    @Bean
    public Binding assembleDeadLetterBinding(Queue assembleDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(assembleDeadLetterQueue).to(deadLetterExchange)
                .with(PipelineQueues.ASSEMBLE_DEAD_LETTER_QUEUE);
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
     *
     * <p>Built from {@link SimpleRabbitListenerContainerFactoryConfigurer}
     * rather than {@code new SimpleRabbitListenerContainerFactory()} so
     * {@code spring.rabbitmq.listener.simple.retry} actually applies -
     * constructing the factory directly silently ignored that whole
     * configuration block, so a failing message skipped straight past its 3
     * configured retries to the dead-letter queue.
     * {@code defaultRequeueRejected(false)} still governs what happens once
     * retries (now real) are exhausted: reject to the DLQ instead of
     * looping forever.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            OcrProperties ocrProperties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(ocrProperties.worker().concurrency());
        factory.setMaxConcurrentConsumers(ocrProperties.worker().maxConcurrency());
        return factory;
    }

    /**
     * Shared by the translate, TTS and assemble listeners: their concurrency
     * is a single per-page cap ({@code sarvam.pipeline.concurrency}) rather
     * than the two-tier concurrent/max split OCR uses, since each page of a
     * document only ever makes one paid call at a time per stage.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory pipelineListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            SarvamProperties sarvamProperties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(sarvamProperties.pipeline().concurrency());
        factory.setMaxConcurrentConsumers(sarvamProperties.pipeline().concurrency());
        return factory;
    }
}
