package com.aisourceshandler.infrastructure;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VideoSubtitleExtractor {
    public record VideoResult(String title, String author, long durationSeconds, String webpageUrl,
                              List<ContentBlock> blocks) {}

    private static final Set<String> ALLOWED_HOSTS = Set.of("bilibili.com", "www.bilibili.com", "b23.tv");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s+-->\\s+(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})");

    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final LocalStore store;

    public VideoSubtitleExtractor(AppProperties properties, ObjectMapper mapper, LocalStore store) {
        this.properties = properties;
        this.mapper = mapper;
        this.store = store;
    }

    public boolean available() {
        try {
            Process process = new ProcessBuilder(properties.video().ytDlpPath(), "--version")
                    .redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    public VideoResult extract(UUID packageId, SourceItem item, String rawUrl) {
        URI uri = validate(rawUrl);
        if (!available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "YT_DLP_NOT_AVAILABLE",
                    "yt-dlp 不可用，请检查 YT_DLP_PATH。", true);
        }
        Path outputDirectory = store.packageRoot(packageId).resolve("inputs/video");
        try {
            Files.createDirectories(outputDirectory);
            List<String> metadataCommand = baseCommand();
            metadataCommand.addAll(List.of("--skip-download", "--dump-single-json", "--no-playlist", uri.toString()));
            ProcessResult metadataResult = run(metadataCommand, outputDirectory);
            if (metadataResult.exitCode != 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_METADATA_FAILED",
                        "无法读取 Bilibili 视频信息。", true);
            }
            JsonNode metadata = mapper.readTree(metadataResult.output);
            List<String> subtitleCommand = baseCommand();
            subtitleCommand.addAll(List.of("--skip-download", "--write-subs", "--write-auto-subs",
                    "--sub-langs", "zh-Hans,zh-Hant,zh,en", "--sub-format", "vtt/srt/best",
                    "--output", outputDirectory.resolve("%(id)s.%(ext)s").toString(), uri.toString()));
            ProcessResult subtitleResult = run(subtitleCommand, outputDirectory);
            Optional<Path> subtitle = findSubtitle(outputDirectory);
            if (subtitleResult.exitCode != 0 || subtitle.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_SUBTITLE_UNAVAILABLE",
                        "该视频没有可获取的字幕，MVP 不进行音频转写。", false);
            }
            String text = Files.readString(subtitle.get(), StandardCharsets.UTF_8);
            List<ContentBlock> blocks = parseSubtitles(packageId, item, text);
            if (blocks.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_SUBTITLE_UNAVAILABLE",
                        "字幕为空或格式不受支持。", false);
            }
            return new VideoResult(metadata.path("title").asText(item.originalName()),
                    metadata.path("uploader").asText(""), metadata.path("duration").asLong(),
                    metadata.path("webpage_url").asText(rawUrl), blocks);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_SUBTITLE_UNAVAILABLE",
                    "视频字幕处理失败。", false);
        }
    }

    private URI validate(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress()) {
                    throw new IllegalArgumentException();
                }
            }
            return uri;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_URL_UNSUPPORTED",
                    "仅支持 HTTPS Bilibili 或 b23.tv 链接。", false);
        }
    }

    private List<String> baseCommand() {
        List<String> command = new ArrayList<>();
        command.add(properties.video().ytDlpPath());
        if (properties.video().cookiesFromBrowser() != null
                && !properties.video().cookiesFromBrowser().isBlank()) {
            command.addAll(List.of("--cookies-from-browser", properties.video().cookiesFromBrowser()));
        }
        return command;
    }

    private ProcessResult run(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).start();
        boolean complete = process.waitFor(properties.video().timeoutSeconds(), TimeUnit.SECONDS);
        if (!complete) {
            process.destroyForcibly();
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "VIDEO_PROCESS_TIMEOUT",
                    "视频处理超时。", true);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private Optional<Path> findSubtitle(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".vtt") || name.endsWith(".srt");
            }).findFirst();
        }
    }

    private List<ContentBlock> parseSubtitles(UUID packageId, SourceItem item, String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n");
        List<ContentBlock> blocks = new ArrayList<>();
        long start = -1;
        long end = -1;
        StringBuilder caption = new StringBuilder();
        int sequence = 0;
        for (String line : lines) {
            Matcher matcher = TIMESTAMP.matcher(line);
            if (matcher.find()) {
                if (!caption.isEmpty() && start >= 0) {
                    blocks.add(transcript(packageId, item, sequence++, start, end, caption.toString()));
                    caption.setLength(0);
                }
                start = millis(matcher, 1);
                end = millis(matcher, 5);
            } else if (start >= 0 && !line.isBlank() && !line.matches("\\d+")) {
                String clean = line.replaceAll("<[^>]+>", "").strip();
                if (!clean.isBlank() && !caption.toString().endsWith(clean)) {
                    if (!caption.isEmpty()) caption.append(' ');
                    caption.append(clean);
                }
            }
        }
        if (!caption.isEmpty() && start >= 0) {
            blocks.add(transcript(packageId, item, sequence, start, end, caption.toString()));
        }
        return blocks;
    }

    private ContentBlock transcript(UUID packageId, SourceItem item, int sequence, long start, long end, String text) {
        return new ContentBlock("blk_" + UUID.randomUUID().toString().replace("-", ""), packageId, item.id(),
                BlockType.TRANSCRIPT, sequence, text,
                new SourceRef(SourceRefKind.VIDEO_TIME_RANGE, null, null, null, start, end),
                1.0, Map.of());
    }

    private long millis(Matcher matcher, int offset) {
        return (Long.parseLong(matcher.group(offset)) * 3600
                + Long.parseLong(matcher.group(offset + 1)) * 60
                + Long.parseLong(matcher.group(offset + 2))) * 1000
                + Long.parseLong(matcher.group(offset + 3));
    }

    private record ProcessResult(int exitCode, String output) {}
}
