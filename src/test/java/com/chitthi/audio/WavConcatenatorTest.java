package com.chitthi.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WavConcatenatorTest {

    private static final AudioFormat FORMAT_22050 = new AudioFormat(22050f, 16, 1, true, false);
    private static final AudioFormat FORMAT_16000 = new AudioFormat(16000f, 16, 1, true, false);

    @Test
    void frameCountOfTheResultEqualsTheSumOfTheInputs() throws IOException, UnsupportedAudioFileException {
        byte[] wavA = generateWav(FORMAT_22050, 100);
        byte[] wavB = generateWav(FORMAT_22050, 200);
        byte[] wavC = generateWav(FORMAT_22050, 150);

        byte[] result = WavConcatenator.concat(List.of(wavA, wavB, wavC));

        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(result))) {
            assertThat(stream.getFrameLength()).isEqualTo(450);
            assertThat(stream.getFormat().matches(FORMAT_22050)).isTrue();
        }
    }

    @Test
    void aSingleWavIsReturnedUnchangedInFrameCount() throws IOException, UnsupportedAudioFileException {
        byte[] wav = generateWav(FORMAT_22050, 42);

        byte[] result = WavConcatenator.concat(List.of(wav));

        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(result))) {
            assertThat(stream.getFrameLength()).isEqualTo(42);
        }
    }

    @Test
    void aMismatchedSampleRateThrows() {
        byte[] wavA = generateWav(FORMAT_22050, 100);
        byte[] wavB = generateWav(FORMAT_16000, 100);

        assertThatThrownBy(() -> WavConcatenator.concat(List.of(wavA, wavB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void anEmptyListIsRejected() {
        assertThatThrownBy(() -> WavConcatenator.concat(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] generateWav(AudioFormat format, int frameCount) {
        int bytesPerFrame = format.getFrameSize();
        byte[] data = new byte[frameCount * bytesPerFrame];
        new Random(42).nextBytes(data);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(data), format, frameCount)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
