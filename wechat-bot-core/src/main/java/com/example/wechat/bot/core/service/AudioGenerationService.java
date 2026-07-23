package com.example.wechat.bot.core.service;

import com.example.wechat.bot.common.config.AudioGenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AudioGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(AudioGenerationService.class);

    private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

    private final AudioGenProperties audioGenProperties;

    public AudioGenerationService(AudioGenProperties audioGenProperties) {
        this.audioGenProperties = audioGenProperties;
    }

    /**
     * 使用 edge-tts 将文本合成为语音（MP3 格式）
     * 先尝试直接执行 edge-tts 命令，失败时回退到 python3 -m edge_tts
     *
     * @param text 要朗读的文本
     * @return MP3 音频字节数组，失败返回 null
     */
    public byte[] generate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 生成唯一临时文件路径，避免并发冲突
        String fileName = "tts_" + UUID.randomUUID().toString() + ".mp3";
        Path tmpFile = Path.of(TMP_DIR, fileName);

        String voice = audioGenProperties.getVoice();
        String rate = audioGenProperties.getRate();
        String volume = audioGenProperties.getVolume();
        int timeoutSeconds = audioGenProperties.getTimeoutSeconds();
        String edgeTtsPath = audioGenProperties.getEdgeTtsPath();

        // 先尝试直接用 edge-tts 命令，失败时回退到 python3 -m edge_tts
        String[][] cmdTemplates = {
            {edgeTtsPath},
            {"python3", "-m", "edge_tts"}
        };

        for (String[] cmdPrefix : cmdTemplates) {
            String[] cmd = buildCmd(cmdPrefix, text, voice, rate, volume, tmpFile.toString());

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true); // 捕获 stderr 用于诊断

                logger.info("执行 TTS: voice={}, rate={}, volume={}", voice, rate, volume);

                Process process = pb.start();

                // 等待进程完成，带超时
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    logger.warn("TTS 进程超时（{}s），强制终止", timeoutSeconds);
                    // 超时后尝试下一个命令（如果有）
                    continue;
                }

                int exitCode = process.exitValue();
                String output = new String(process.getInputStream().readAllBytes()).trim();

                if (exitCode != 0) {
                    logger.warn("TTS 进程退出码={}: {}", exitCode, output);
                    // 第一个命令失败时继续尝试回退方式，第二个命令失败则结束循环
                    if (cmdPrefix == cmdTemplates[cmdTemplates.length - 1]) {
                        break;
                    }
                    logger.warn("尝试 python3 -m edge_tts 回退...");
                    continue;
                }

                // 读取生成的音频文件
                if (!Files.exists(tmpFile) || Files.size(tmpFile) == 0) {
                    logger.warn("TTS 输出文件不存在或为空: {}", tmpFile);
                    break;
                }

                byte[] audioBytes = Files.readAllBytes(tmpFile);
                logger.info("TTS 成功，音频大小={} bytes", audioBytes.length);
                return audioBytes;

            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("No such file or directory")) {
                    logger.warn("命令未找到: {}，尝试回退方式...", cmdPrefix[0]);
                    // 如果是第一个命令失败，循环会尝试第二个；如果是第二个也失败，循环结束
                    continue;
                }
                logger.warn("TTS 执行失败: {}", msg);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("TTS 进程被中断", e);
                break;
            }
        }

        // 失败后清理临时文件
        try {
            Files.deleteIfExists(tmpFile);
        } catch (IOException e) {
            logger.warn("清理临时文件失败: {}", tmpFile, e);
        }

        return null;
    }

    private static String[] buildCmd(String[] prefix, String text, String voice,
                                      String rate, String volume, String tmpFilePath) {
        String[] args = {
            "-t", text,
            "--voice", voice,
            "--rate", rate,
            "--volume", volume,
            "--write-media", tmpFilePath
        };
        String[] result = new String[prefix.length + args.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(args, 0, result, prefix.length, args.length);
        return result;
    }
}
