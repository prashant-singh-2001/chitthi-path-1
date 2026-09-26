package com.chitthi.ocr;

import com.chitthi.ocr.result.DigitiseResultParser;
import com.chitthi.ocr.result.ParsedPage;
import com.chitthi.ocr.service.OcrBatchStateService;
import com.chitthi.ocr.service.OcrPollSchedule;
import com.chitthi.ocr.service.OcrResultApplier;
import com.chitthi.ocr.service.OcrStatusPoller;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.dto.DownloadUrlResponse;
import com.chitthi.sarvam.dto.JobStatus;
import com.chitthi.sarvam.dto.JobStatusResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of {@link OcrStatusPoller}'s branching, with every
 * collaborator mocked. A Testcontainers-backed test proving the poller's
 * native SKIP LOCKED claim queries against a real Postgres is folded into
 * the end-to-end {@code OcrPipelineIntegrationTest} instead of duplicated
 * here - this class is about the control flow, not the SQL.
 */
class OcrStatusPollerTest {

    private final OcrBatchStateService stateService = mock(OcrBatchStateService.class);
    private final OcrResultApplier resultApplier = mock(OcrResultApplier.class);
    private final OcrPollSchedule pollSchedule = mock(OcrPollSchedule.class);
    private final SarvamClient sarvamClient = mock(SarvamClient.class);
    private final DigitiseResultParser resultParser = mock(DigitiseResultParser.class);
    private final OcrProperties ocrProperties = new OcrProperties(
            new OcrProperties.Worker(true, 2, 4),
            new OcrProperties.Poller(true, 20),
            new OcrProperties.Poll(Duration.ofSeconds(5), 1.5, Duration.ofSeconds(60), 0.2, 30),
            new OcrProperties.Dispatch(Duration.ofSeconds(60), 3),
            33_554_432L,
            new OcrProperties.Result(33_554_432L, List.of("markdown")));

    private final OcrStatusPoller poller = new OcrStatusPoller(
            stateService, resultApplier, pollSchedule, sarvamClient, resultParser, ocrProperties);

    @Test
    void pollDueJobs_appliesResultWhenSarvamReportsCompleted() {
        UUID batchId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var claimed = new OcrBatchStateService.ClaimedForPoll(batchId, documentId, "1-10", "job-1", 1);
        when(stateService.claimDueForPoll(anyInt())).thenReturn(List.of(claimed));
        when(sarvamClient.getJobStatus("job-1")).thenReturn(new JobStatusResponse("job-1", JobStatus.COMPLETED));
        when(sarvamClient.getDownloadUrl("job-1")).thenReturn(new DownloadUrlResponse("https://example/result.zip"));
        when(sarvamClient.downloadResult("https://example/result.zip")).thenReturn("zip-bytes".getBytes());
        when(resultParser.parse(any(), any())).thenReturn(List.of(new ParsedPage(1, "some text")));

        poller.pollDueJobs();

        verify(resultApplier).applyParsedResult(eq(batchId), any());
        verify(resultApplier, never()).markBatchFailed(any(), any());
    }

    @Test
    void pollDueJobs_marksFailedWhenNoTextCouldBeRecoveredFromACompletedJob() {
        UUID batchId = UUID.randomUUID();
        var claimed = new OcrBatchStateService.ClaimedForPoll(batchId, UUID.randomUUID(), "1-10", "job-1", 1);
        when(stateService.claimDueForPoll(anyInt())).thenReturn(List.of(claimed));
        when(sarvamClient.getJobStatus("job-1")).thenReturn(new JobStatusResponse("job-1", JobStatus.COMPLETED));
        when(sarvamClient.getDownloadUrl("job-1")).thenReturn(new DownloadUrlResponse("https://example/result.zip"));
        when(sarvamClient.downloadResult(any())).thenReturn("zip-bytes".getBytes());
        when(resultParser.parse(any(), any())).thenReturn(List.of());

        poller.pollDueJobs();

        verify(resultApplier).markBatchFailed(eq(batchId), any());
        verify(resultApplier, never()).applyParsedResult(any(), any());
    }

    @Test
    void pollDueJobs_marksFailedWhenSarvamReportsFailed() {
        UUID batchId = UUID.randomUUID();
        var claimed = new OcrBatchStateService.ClaimedForPoll(batchId, UUID.randomUUID(), "1-10", "job-1", 1);
        when(stateService.claimDueForPoll(anyInt())).thenReturn(List.of(claimed));
        when(sarvamClient.getJobStatus("job-1")).thenReturn(new JobStatusResponse("job-1", JobStatus.FAILED));

        poller.pollDueJobs();

        verify(resultApplier).markBatchFailed(eq(batchId), any());
        verify(sarvamClient, never()).getDownloadUrl(any());
    }

    @Test
    void pollDueJobs_leavesAStillRunningJobAloneWhenUnderTheAttemptCeiling() {
        UUID batchId = UUID.randomUUID();
        var claimed = new OcrBatchStateService.ClaimedForPoll(batchId, UUID.randomUUID(), "1-10", "job-1", 5);
        when(stateService.claimDueForPoll(anyInt())).thenReturn(List.of(claimed));
        when(sarvamClient.getJobStatus("job-1")).thenReturn(new JobStatusResponse("job-1", JobStatus.RUNNING));
        when(pollSchedule.hasExceededMaxAttempts(5)).thenReturn(false);

        poller.pollDueJobs();

        verify(resultApplier, never()).markBatchFailed(any(), any());
    }

    @Test
    void pollDueJobs_givesUpOnceTheAttemptCeilingIsExceeded() {
        UUID batchId = UUID.randomUUID();
        var claimed = new OcrBatchStateService.ClaimedForPoll(batchId, UUID.randomUUID(), "1-10", "job-1", 30);
        when(stateService.claimDueForPoll(anyInt())).thenReturn(List.of(claimed));
        when(sarvamClient.getJobStatus("job-1")).thenReturn(new JobStatusResponse("job-1", JobStatus.RUNNING));
        when(pollSchedule.hasExceededMaxAttempts(30)).thenReturn(true);

        poller.pollDueJobs();

        verify(resultApplier).markBatchFailed(eq(batchId), any());
    }

    @Test
    void redispatchStalledBatches_claimsStalledBatchesFromTheStateService() {
        // The republish itself now happens inside OcrBatchStateService's own
        // transaction (see OcrBatchStateServiceTest) - this poller only needs
        // to drive the claim and not blow up when batches come back.
        var claimed = new OcrBatchStateService.ClaimedForRedispatch(
                UUID.randomUUID(), UUID.randomUUID(), "hi", "1-10");
        when(stateService.claimStalledForRedispatch(anyInt(), anyInt(), any())).thenReturn(List.of(claimed));

        poller.redispatchStalledBatches();

        verify(stateService).claimStalledForRedispatch(anyInt(), anyInt(), any());
    }
}
