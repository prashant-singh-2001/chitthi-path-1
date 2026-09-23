package com.chitthi.ocr.service;

import com.chitthi.ocr.OcrProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPollScheduleTest {

    @Test
    void delayAfter_growsGeometricallyWhenThereIsNoJitter() {
        OcrPollSchedule schedule = new OcrPollSchedule(ocrProperties(pollConfig(0.0)), new Random());

        assertThat(schedule.delayAfter(1)).isEqualTo(Duration.ofMillis(5000));
        assertThat(schedule.delayAfter(2)).isEqualTo(Duration.ofMillis(7500));
        assertThat(schedule.delayAfter(3)).isEqualTo(Duration.ofMillis(11250));
    }

    @Test
    void delayAfter_capsAtMaxDelay() {
        OcrPollSchedule schedule = new OcrPollSchedule(ocrProperties(pollConfig(0.0)), new Random());

        // 5000 * 1.5^7 ~= 57000ms, ^8 ~= 85500ms - past the 60s cap by poll 8.
        assertThat(schedule.delayAfter(8)).isEqualTo(Duration.ofSeconds(60));
        assertThat(schedule.delayAfter(30)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void delayAfter_staysWithinTheConfiguredJitterBound() {
        OcrPollSchedule schedule = new OcrPollSchedule(ocrProperties(pollConfig(0.2)), new Random());

        // Poll 1's uncapped delay is exactly the 5s initial delay; jitter is +-20%.
        for (int i = 0; i < 200; i++) {
            long millis = schedule.delayAfter(1).toMillis();
            assertThat(millis).isBetween(4000L, 6000L);
        }
    }

    @Test
    void hasExceededMaxAttempts_isFalseBelowTheConfiguredCeiling() {
        OcrPollSchedule schedule = new OcrPollSchedule(ocrProperties(pollConfig(0.0)), new Random());

        assertThat(schedule.hasExceededMaxAttempts(29)).isFalse();
        assertThat(schedule.hasExceededMaxAttempts(30)).isTrue();
        assertThat(schedule.hasExceededMaxAttempts(31)).isTrue();
    }

    private OcrProperties.Poll pollConfig(double jitterRatio) {
        return new OcrProperties.Poll(Duration.ofSeconds(5), 1.5, Duration.ofSeconds(60), jitterRatio, 30);
    }

    private OcrProperties ocrProperties(OcrProperties.Poll poll) {
        return new OcrProperties(
                new OcrProperties.Worker(true, 2, 4),
                new OcrProperties.Poller(true, Duration.ofSeconds(1), 20),
                poll,
                new OcrProperties.Dispatch(Duration.ofSeconds(60), 3),
                33_554_432L,
                new OcrProperties.Result(33_554_432L, List.of("markdown")));
    }
}
