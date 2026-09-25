package com.chitthi.assemble;

import com.chitthi.assemble.message.AssembleMessage;
import com.chitthi.audio.WavConcatenator;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consumes {@code assemble.queue}: once every page of a document has reached
 * a terminal per-page state, stitches each language track's page-level WAVs
 * into one document-level track and settles the document's final status.
 *
 * <p>Idempotent by construction - re-running writes the same track object
 * keys with the same bytes and only promotes pages still AUDIO_DONE - so
 * unlike every earlier stage this one makes no paid calls and needs no
 * per-document claim to be safe under a duplicate delivery.
 */
@Component
public class DocumentAssembler {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssembler.class);
    private static final List<String> TRACKS = List.of("orig", "en");
    private static final Set<PageStatus> IN_FLIGHT_STATUSES =
            Set.of(PageStatus.PENDING, PageStatus.OCR_DONE, PageStatus.TRANSLATED);

    private final PageRepository pageRepository;
    private final ObjectStorageService storageService;
    private final AssembleStateService stateService;

    public DocumentAssembler(PageRepository pageRepository, ObjectStorageService storageService,
                              AssembleStateService stateService) {
        this.pageRepository = pageRepository;
        this.storageService = storageService;
        this.stateService = stateService;
    }

    @RabbitListener(queues = PipelineQueues.ASSEMBLE_QUEUE, containerFactory = "pipelineListenerContainerFactory",
            autoStartup = "${chitthi.assemble.worker.enabled:true}")
    public void onMessage(AssembleMessage message) {
        MDC.put("documentId", message.documentId().toString());
        try {
            handle(message.documentId());
        } finally {
            MDC.remove("documentId");
        }
    }

    private void handle(UUID documentId) {
        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);
        if (pages.isEmpty()) {
            return;
        }
        boolean anyInFlight = pages.stream().anyMatch(p -> IN_FLIGHT_STATUSES.contains(p.getStatus()));
        if (anyInFlight) {
            log.info("Document {} still has pages in flight; deferring assembly", documentId);
            return;
        }

        for (String track : TRACKS) {
            assembleTrack(documentId, track, pages);
        }

        boolean anyFailed = pages.stream().anyMatch(p -> p.getStatus() == PageStatus.FAILED);
        stateService.finalizeDocument(documentId, anyFailed);
    }

    private void assembleTrack(UUID documentId, String track, List<Page> pages) {
        List<byte[]> pageWavs = new ArrayList<>();
        for (Page page : pages) {
            if (page.getStatus() == PageStatus.FAILED) {
                continue;
            }
            String key = "documents/%s/audio/%s/%03d.wav".formatted(documentId, track, page.getPageNo());
            if (storageService.exists(key)) {
                pageWavs.add(storageService.getObject(key));
            }
        }
        if (pageWavs.isEmpty()) {
            // Either every page failed, or (for "orig") the document's source
            // language isn't one Bulbul supports - no track to write at all.
            return;
        }
        byte[] trackWav = WavConcatenator.concat(pageWavs);
        storageService.putObject("documents/%s/audio/%s.wav".formatted(documentId, track), trackWav, "audio/wav");
    }
}
