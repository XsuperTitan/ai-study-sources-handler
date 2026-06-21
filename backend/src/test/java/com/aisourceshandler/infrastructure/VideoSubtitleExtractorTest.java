package com.aisourceshandler.infrastructure;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoSubtitleExtractorTest {
    @TempDir
    Path temp;

    @Test
    void extractsExistingSubtitlesWithTimeRanges() throws Exception {
        Path tool = fakeYtDlp(true);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalStore store = new LocalStore(temp.resolve("store"), mapper);
        VideoSubtitleExtractor extractor = new VideoSubtitleExtractor(properties(tool), mapper, store);
        UUID packageId = UUID.randomUUID();
        SourceItem item = videoItem(packageId);

        VideoSubtitleExtractor.VideoResult result = extractor.extract(packageId, item,
                "https://www.bilibili.com/video/BV1xx411c7mD");

        assertThat(result.title()).isEqualTo("Video");
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().getFirst().sourceRef().kind()).isEqualTo(SourceRefKind.VIDEO_TIME_RANGE);
        assertThat(result.blocks().getFirst().content()).contains("hello world");
    }

    @Test
    void failsClearlyWhenSubtitleIsMissing() throws Exception {
        Path tool = fakeYtDlp(false);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalStore store = new LocalStore(temp.resolve("store"), mapper);
        VideoSubtitleExtractor extractor = new VideoSubtitleExtractor(properties(tool), mapper, store);

        assertThatThrownBy(() -> extractor.extract(UUID.randomUUID(), videoItem(UUID.randomUUID()),
                "https://www.bilibili.com/video/BV1xx411c7mD"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("VIDEO_SUBTITLE_UNAVAILABLE");
    }

    @Test
    void reportsMissingYtDlpAsRetryableCapabilityError() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalStore store = new LocalStore(temp.resolve("store"), mapper);
        VideoSubtitleExtractor extractor = new VideoSubtitleExtractor(properties(temp.resolve("missing-yt-dlp.cmd")),
                mapper, store);

        assertThatThrownBy(() -> extractor.extract(UUID.randomUUID(), videoItem(UUID.randomUUID()),
                "https://www.bilibili.com/video/BV1xx411c7mD"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("YT_DLP_NOT_AVAILABLE");
    }

    private Path fakeYtDlp(boolean writeSubtitle) throws Exception {
        Path tool = temp.resolve("yt-dlp.cmd");
        String subtitleBlock = writeSubtitle
                ? """
                echo WEBVTT> sample.vtt
                echo.>> sample.vtt
                echo 00:00:01.000 --^> 00:00:03.000>> sample.vtt
                echo hello world>> sample.vtt
                """
                : "";
        Files.writeString(tool, """
                @echo off
                echo %%* | findstr /C:"--version" >nul && (echo 2026.01.01 & exit /b 0)
                echo %%* | findstr /C:"--dump-single-json" >nul && (echo {"title":"Video","duration":42,"webpage_url":"https://www.bilibili.com/video/BV1xx411c7mD","uploader":"up"} & exit /b 0)
                %s
                exit /b 0
                """.formatted(subtitleBlock), StandardCharsets.UTF_8);
        return tool;
    }

    private SourceItem videoItem(UUID packageId) {
        return new SourceItem(UUID.randomUUID(), packageId, SourceKind.VIDEO, "Video", null, 0,
                Map.of("url", "https://www.bilibili.com/video/BV1xx411c7mD"));
    }

    private AppProperties properties(Path ytDlp) {
        return new AppProperties(
                false,
                temp.resolve("store").toString(),
                new AppProperties.Upload(20, 104857600, 10485760, 2097152, 300),
                new AppProperties.Pdf(12, 144),
                new AppProperties.Jobs(1, 2, 8),
                new AppProperties.Provider("test-key", "http://localhost", "deepseek-chat", null, null),
                new AppProperties.Provider("test-key", "http://localhost", "qwen-vl-max", null, null),
                new AppProperties.QwenImage("", "http://localhost", "qwen-image-2.0-pro", false,
                        true, false, 0, null, null),
                new AppProperties.Provider("", "http://localhost", "wanx", null, null),
                new AppProperties.Video(ytDlp.toString(), 5, "")
        );
    }
}
