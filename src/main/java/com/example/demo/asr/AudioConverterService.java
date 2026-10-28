package com.example.demo.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class AudioConverterService {

    private static final Logger logger = LoggerFactory.getLogger(AudioConverterService.class);

    private static final byte[] AMR_HEADER = "#!AMR\n".getBytes();
    private static final byte[] AMRWB_HEADER = "#!AMR-WB\n".getBytes();
    private static final byte[] SILK_HEADER = "#!SILK".getBytes();

    @Value("${audio.converter.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.guard.ffmpeg-timeout-seconds:30}")
    private int ffmpegTimeoutSeconds = 30;

    @Value("${app.guard.ffmpeg-max-audio-seconds:300}")
    private int ffmpegMaxAudioSeconds = 300;

    @Value("${app.guard.ffmpeg.max-concurrent:1}")
    private int ffmpegMaxConcurrent = 1;

    private volatile Semaphore ffmpegSlots;

    private boolean ffmpegAvailable = false;

    private Semaphore slots() {
        Semaphore current = ffmpegSlots;
        if (current == null) {
            synchronized (this) {
                if (ffmpegSlots == null) {
                    ffmpegSlots = new Semaphore(Math.max(1, ffmpegMaxConcurrent));
                }
                current = ffmpegSlots;
            }
        }
        return current;
    }

    /**
     * 统一的 ffmpeg 调用：禁止交互、限制时长与线程数，stderr 落到临时文件避免管道死锁，
     * 超时强制结束进程。
     */
    private void runFfmpeg(List<String> extraArgs, String operationName) throws Exception {
        Semaphore permit = slots();
        if (!permit.tryAcquire(ffmpegTimeoutSeconds, TimeUnit.SECONDS)) {
            throw new IOException("音频处理繁忙，请稍后再试");
        }
        File logFile = null;
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-nostdin");
            command.add("-y");
            command.add("-loglevel");
            command.add("error");
            command.add("-t");
            command.add(String.valueOf(Math.max(1, ffmpegMaxAudioSeconds)));
            command.add("-threads");
            command.add("1");
            command.addAll(extraArgs);

            logFile = File.createTempFile("ffmpeg_log_", ".log");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile);

            process = pb.start();
            boolean finished = process.waitFor(Math.max(1, ffmpegTimeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("音频转换超时（" + ffmpegTimeoutSeconds + "s）");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.error("{} failed with exit code: {}, log: {}", operationName, exitCode, readLog(logFile));
                throw new RuntimeException(operationName + " failed, exit code: " + exitCode);
            }
            logger.debug("{} output: {}", operationName, readLog(logFile));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (logFile != null && logFile.exists()) {
                logFile.delete();
            }
            permit.release();
        }
    }

    private String readLog(File logFile) {
        if (logFile == null || !logFile.exists()) {
            return "";
        }
        try (InputStream in = Files.newInputStream(logFile.toPath())) {
            byte[] bytes = in.readNBytes(8192);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public byte[] convertToWav16k16bitMono(byte[] inputData, String inputExtension) throws Exception {
        logger.info("Starting audio conversion to WAV, input size: {} bytes, extension: {}", inputData.length, inputExtension);

        if (inputData == null || inputData.length == 0) {
            throw new IllegalArgumentException("Input data is empty");
        }

        logDataHeader(inputData);

        String detectedFormat = detectAudioFormat(inputData);
        logger.info("Detected audio format: {}", detectedFormat);

        byte[] processedData = inputData;

        if ("silk".equals(detectedFormat)) {
            byte[] rawPayload = extractRawPayload(inputData);
            logger.info("SILK detected, trying raw payload ({}) bytes as PCM", rawPayload.length);
            return pcmToWav(rawPayload);
        } else if ("amr".equals(detectedFormat) || "amr_wb".equals(detectedFormat)) {
            processedData = ensureAmrHeader(inputData);
        } else if ("raw".equals(detectedFormat)) {
            if (inputData.length % 2 == 0 && inputData.length >= 320) {
                logger.info("Data appears to be raw PCM, converting to WAV");
                return pcmToWav(inputData);
            }
            processedData = ensureAmrHeader(inputData);
        } else {
            processedData = ensureAmrHeader(inputData);
        }

        if (isFfmpegAvailable()) {
            logger.info("Using system ffmpeg for WAV conversion");
            return convertToWavWithFfmpeg(processedData, detectedFormat);
        }

        throw new RuntimeException("ffmpeg is not available, cannot convert audio");
    }

    private byte[] pcmToWav(byte[] pcmData) {
        int sampleRate = 16000;
        int channels = 1;
        int bitsPerSample = 16;
        int blockAlign = channels * (bitsPerSample / 8);
        int byteRate = sampleRate * blockAlign;
        int dataSize = pcmData.length;
        int chunkSize = 36 + dataSize;

        byte[] wavHeader = new byte[44];
        
        wavHeader[0] = 'R'; wavHeader[1] = 'I'; wavHeader[2] = 'F'; wavHeader[3] = 'F';
        wavHeader[4] = (byte) (chunkSize & 0xff);
        wavHeader[5] = (byte) ((chunkSize >> 8) & 0xff);
        wavHeader[6] = (byte) ((chunkSize >> 16) & 0xff);
        wavHeader[7] = (byte) ((chunkSize >> 24) & 0xff);
        
        wavHeader[8] = 'W'; wavHeader[9] = 'A'; wavHeader[10] = 'V'; wavHeader[11] = 'E';
        wavHeader[12] = 'f'; wavHeader[13] = 'm'; wavHeader[14] = 't'; wavHeader[15] = ' ';
        wavHeader[16] = 16; wavHeader[17] = 0; wavHeader[18] = 0; wavHeader[19] = 0;
        wavHeader[20] = 1; wavHeader[21] = 0;
        wavHeader[22] = (byte) channels; wavHeader[23] = 0;
        wavHeader[24] = (byte) (sampleRate & 0xff);
        wavHeader[25] = (byte) ((sampleRate >> 8) & 0xff);
        wavHeader[26] = (byte) ((sampleRate >> 16) & 0xff);
        wavHeader[27] = (byte) ((sampleRate >> 24) & 0xff);
        wavHeader[28] = (byte) (byteRate & 0xff);
        wavHeader[29] = (byte) ((byteRate >> 8) & 0xff);
        wavHeader[30] = (byte) ((byteRate >> 16) & 0xff);
        wavHeader[31] = (byte) ((byteRate >> 24) & 0xff);
        wavHeader[32] = (byte) blockAlign; wavHeader[33] = 0;
        wavHeader[34] = (byte) bitsPerSample; wavHeader[35] = 0;
        
        wavHeader[36] = 'd'; wavHeader[37] = 'a'; wavHeader[38] = 't'; wavHeader[39] = 'a';
        wavHeader[40] = (byte) (dataSize & 0xff);
        wavHeader[41] = (byte) ((dataSize >> 8) & 0xff);
        wavHeader[42] = (byte) ((dataSize >> 16) & 0xff);
        wavHeader[43] = (byte) ((dataSize >> 24) & 0xff);

        byte[] wavData = new byte[44 + pcmData.length];
        System.arraycopy(wavHeader, 0, wavData, 0, 44);
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);
        
        logger.info("PCM converted to WAV, size: {} bytes", wavData.length);
        return wavData;
    }

    private byte[] convertToWavWithFfmpeg(byte[] inputData, String detectedFormat) throws Exception {
        File inputFile = null;
        File outputFile = null;

        try {
            String ext = "amr";
            inputFile = File.createTempFile("audio_input", "." + ext);
            Files.write(inputFile.toPath(), inputData);

            outputFile = File.createTempFile("audio_output", ".wav");

            runFfmpeg(List.of(
                "-i", inputFile.getAbsolutePath(),
                "-acodec", "pcm_s16le",
                "-ar", "16000",
                "-ac", "1",
                "-f", "wav",
                "-map_metadata", "-1",
                "-bitexact",
                outputFile.getAbsolutePath()
            ), "ffmpeg WAV conversion");

            byte[] wavData = Files.readAllBytes(outputFile.toPath());
            logger.info("Audio converted with ffmpeg to WAV, size: {} bytes", wavData.length);
            return wavData;
        } finally {
            if (inputFile != null && inputFile.exists()) {
                inputFile.delete();
            }
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    /**
     * 通用音频转 16kHz/16bit/单声道 WAV（Web 录音 webm/opus、mp3、m4a 等），
     * 直接交给 ffmpeg 按内容识别，不做 AMR/SILK 头处理。
     */
    public byte[] convertAnyToWav16k16bitMono(byte[] inputData, String inputExtension) throws Exception {
        logger.info("Generic audio conversion to WAV, input size: {} bytes, extension: {}",
                inputData.length, inputExtension);
        if (inputData == null || inputData.length == 0) {
            throw new IllegalArgumentException("Input data is empty");
        }
        if (!isFfmpegAvailable()) {
            throw new RuntimeException("ffmpeg is not available, cannot convert audio");
        }

        String safeExt = inputExtension == null ? "webm"
                : inputExtension.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (safeExt.isEmpty()) {
            safeExt = "webm";
        }

        File inputFile = null;
        File outputFile = null;
        try {
            inputFile = File.createTempFile("audio_input_", "." + safeExt);
            Files.write(inputFile.toPath(), inputData);

            outputFile = File.createTempFile("audio_output_", ".wav");

            runFfmpeg(List.of(
                    "-i", inputFile.getAbsolutePath(),
                    "-acodec", "pcm_s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    "-f", "wav",
                    outputFile.getAbsolutePath()
            ), "ffmpeg generic conversion");

            byte[] wavData = Files.readAllBytes(outputFile.toPath());
            logger.info("Generic audio converted to WAV, size: {} bytes", wavData.length);
            return wavData;
        } finally {
            if (inputFile != null && inputFile.exists()) {
                inputFile.delete();
            }
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    public byte[] convertToPcm16k16bitMono(byte[] inputData, String inputExtension) throws Exception {
        logger.info("Starting audio conversion, input size: {} bytes, extension: {}", inputData.length, inputExtension);

        if (inputData == null || inputData.length == 0) {
            throw new IllegalArgumentException("Input data is empty");
        }

        logDataHeader(inputData);

        // Auto-detect audio format from header bytes
        String detectedFormat = detectAudioFormat(inputData);
        logger.info("Detected audio format: {}", detectedFormat);

        byte[] processedData = inputData;

        // If data appears to already be raw PCM (no recognizable header),
        // check if it looks like valid 16-bit PCM at expected sample rate
        // then pass through without conversion
        if ("raw".equals(detectedFormat)) {
            // Could be raw PCM already — verify by checking if data size
            // is consistent with 16-bit mono audio (even number of bytes)
            if (inputData.length % 2 == 0 && inputData.length >= 320) {
                logger.info("Data appears to be raw PCM ({} bytes, even-length, no header). Using directly.", inputData.length);
                return inputData;
            }
        }

        // Ensure proper header for the detected format
        if ("silk".equals(detectedFormat)) {
            // Try stripped raw PCM first (WeChat ILink may wrap raw PCM in SILK container)
            byte[] rawPayload = extractRawPayload(inputData);
            logger.info("SILK detected, trying raw payload ({}) bytes as PCM", rawPayload.length);
            return rawPayload;
        } else if ("amr".equals(detectedFormat) || "amr_wb".equals(detectedFormat)) {
        }
        if ("amr".equals(detectedFormat) || "amr_wb".equals(detectedFormat)) {
            processedData = ensureAmrHeader(inputData);
        } else if (!"silk".equals(detectedFormat)) {
            // Unknown format, try adding AMR header as fallback
            logger.info("Unknown audio format, attempting AMR header as fallback");
            processedData = ensureAmrHeader(inputData);
        }

        if (isFfmpegAvailable()) {
            logger.info("Using system ffmpeg for conversion, detected format: {}", detectedFormat);
            return convertWithFfmpeg(processedData, detectedFormat);
        }

        throw new RuntimeException("ffmpeg is not available, cannot convert audio");
    }

    private byte[] convertWithFfmpeg(byte[] inputData, String detectedFormat) throws Exception {
        File inputFile = null;
        File outputFile = null;

        try {
            String ext = "amr";
            inputFile = File.createTempFile("audio_input", "." + ext);
            Files.write(inputFile.toPath(), inputData);

            outputFile = File.createTempFile("audio_output", ".wav");

            logger.info("Using ffmpeg path: {}", ffmpegPath);
            logger.info("Input file: {}, size: {} bytes, format: {}",
                inputFile.getAbsolutePath(), inputData.length, detectedFormat);

            runFfmpeg(List.of(
                "-i", inputFile.getAbsolutePath(),
                "-acodec", "pcm_s16le",
                "-ar", "16000",
                "-ac", "1",
                "-f", "wav",
                "-map_metadata", "-1",
                "-bitexact",
                outputFile.getAbsolutePath()
            ), "ffmpeg conversion");

            logger.info("ffmpeg conversion successful");

            byte[] wavData = Files.readAllBytes(outputFile.toPath());
            logger.info("Audio converted with ffmpeg to WAV, size: {} bytes", wavData.length);

            byte[] pcmData = extractPcmFromWav(wavData);
            logger.info("PCM data extracted, size: {} bytes", pcmData.length);

            return pcmData;
        } finally {
            if (inputFile != null && inputFile.exists()) {
                inputFile.delete();
            }
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    private byte[] ensureAmrHeader(byte[] data) {
        if (data.length >= 6) {
            boolean hasAmrHeader = matchesHeader(data, AMR_HEADER);
            boolean hasAmrWbHeader = matchesHeader(data, AMRWB_HEADER);
            
            if (!hasAmrHeader && !hasAmrWbHeader) {
                logger.info("Adding AMR header to data");
                byte[] result = new byte[data.length + 6];
                System.arraycopy(AMR_HEADER, 0, result, 0, 6);
                System.arraycopy(data, 0, result, 6, data.length);
                return result;
            }
        }
        return data;
    }

    private byte[] extractRawPayload(byte[] data) {
        // Strip 0x02 prefix if present
        int offset = 0;
        if (data.length > 0 && data[0] == 0x02) {
            offset = 1;
        }
        // Find and skip #!SILK_V3 + 2-byte LE header (11 bytes total)
        byte[] silkMarker = "#!SILK_V3".getBytes();
        for (int i = offset; i <= data.length - silkMarker.length; i++) {
            boolean found = true;
            for (int j = 0; j < silkMarker.length; j++) {
                if (data[i + j] != silkMarker[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                int payloadStart = i + 11;  // marker (9) + payload_len (2)
                if (payloadStart < data.length) {
                    byte[] payload = new byte[data.length - payloadStart];
                    System.arraycopy(data, payloadStart, payload, 0, payload.length);
                    return payload;
                }
            }
        }
        // Fallback: just strip 0x02 prefix
        if (offset > 0) {
            byte[] stripped = new byte[data.length - offset];
            System.arraycopy(data, offset, stripped, 0, stripped.length);
            return stripped;
        }
        return data;
    }

    private boolean matchesHeader(byte[] data, byte[] header) {
        return matchesHeader(data, header, 0);
    }

    private boolean matchesHeader(byte[] data, byte[] header, int offset) {
        if (data.length < offset + header.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (data[offset + i] != header[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] extractPcmFromWav(byte[] wavData) {
        if (wavData.length < 44) {
            logger.warn("WAV file too short, returning as-is");
            return wavData;
        }

        int dataStart = 44;
        byte[] pcmData = new byte[wavData.length - dataStart];
        System.arraycopy(wavData, dataStart, pcmData, 0, pcmData.length);
        return pcmData;
    }

    private String detectAudioFormat(byte[] data) {
        if (data == null || data.length < 6) {
            return "unknown";
        }

        if (matchesHeader(data, SILK_HEADER)) {
            return "silk";
        }
        // WeChat voice data may have a leading 0x02 (STX) byte before the SILK header
        if (data.length >= 7 && data[0] == 0x02 && matchesHeader(data, SILK_HEADER, 1)) {
            logger.info("SILK header detected after 0x02 prefix byte");
            return "silk";
        }
        if (matchesHeader(data, AMRWB_HEADER)) {
            return "amr_wb";
        }
        if (matchesHeader(data, AMR_HEADER)) {
            return "amr";
        }
        if (matchesHeader(data, "#!".getBytes())) {
            // Unknown magic number but has a WeChat-style header
            String headerStr = new String(data, 0, Math.min(16, data.length));
            logger.info("Unknown magic header: '{}'", headerStr.replace("\n", "\\n"));
            return "unknown_magic";
        }

        // Check if it looks like raw PCM (no recognizable header)
        // Raw PCM won't start with '#', and binary content suggests it might already be PCM
        if (data[0] != '#') {
            logger.info("No magic header detected, may be raw PCM or unrecognized format");
            return "raw";
        }

        return "unknown";
    }

    private void logDataHeader(byte[] data) {
        StringBuilder sb = new StringBuilder("Data header bytes: ");
        int bytesToShow = Math.min(16, data.length);
        for (int i = 0; i < bytesToShow; i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        logger.info(sb.toString());
        
        if (data.length >= 6) {
            String headerStr = new String(data, 0, 6);
            logger.info("Data header string: '{}'", headerStr);
        }
    }

    public boolean isFfmpegAvailable() {
        if (!ffmpegAvailable) {
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-nostdin", "-version");
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    ffmpegAvailable = false;
                    logger.warn("ffmpeg availability check timed out at path: {}", ffmpegPath);
                } else {
                    ffmpegAvailable = process.exitValue() == 0;
                    if (ffmpegAvailable) {
                        logger.info("ffmpeg is available at: {}", ffmpegPath);
                    }
                }
            } catch (Exception e) {
                logger.warn("ffmpeg not available at path: {}", ffmpegPath, e);
                ffmpegAvailable = false;
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
        return ffmpegAvailable;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }
}
