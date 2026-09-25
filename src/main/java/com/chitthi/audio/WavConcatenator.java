package com.chitthi.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins WAV byte arrays end to end into one WAV, entirely with
 * {@code javax.sound.sampled} - no FFmpeg dependency. Used twice: chunk WAVs
 * into one page's audio, and page WAVs into one document track.
 */
public final class WavConcatenator {

    private WavConcatenator() {
    }

    public static byte[] concat(List<byte[]> wavs) {
        if (wavs == null || wavs.isEmpty()) {
            throw new IllegalArgumentException("At least one WAV is required");
        }
        List<AudioInputStream> streams = new ArrayList<>(wavs.size());
        try {
            AudioFormat format = null;
            long totalFrames = 0;
            for (byte[] wav : wavs) {
                AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
                if (format == null) {
                    format = stream.getFormat();
                } else if (!format.matches(stream.getFormat())) {
                    throw new IllegalArgumentException(
                            "WAV format mismatch: expected %s but got %s".formatted(format, stream.getFormat()));
                }
                streams.add(stream);
                totalFrames += stream.getFrameLength();
            }

            AudioInputStream joined = new AudioInputStream(
                    concatStreams(streams), format, totalFrames);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(joined, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (UnsupportedAudioFileException | IOException e) {
            throw new IllegalArgumentException("Failed to read or write WAV audio", e);
        } finally {
            for (AudioInputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // best-effort close; the streams are backed by in-memory byte arrays
                }
            }
        }
    }

    private static SequenceInputStream concatStreams(List<AudioInputStream> streams) {
        return new SequenceInputStream(java.util.Collections.enumeration(streams));
    }
}
