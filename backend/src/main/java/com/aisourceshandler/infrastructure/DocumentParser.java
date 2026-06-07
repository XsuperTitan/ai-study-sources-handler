package com.aisourceshandler.infrastructure;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.domain.Models.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DocumentParser {
    public record ParseResult(List<ContentBlock> blocks, List<VisionInput> visionInputs) {}
    public record VisionInput(UUID sourceItemId, UUID assetId, String label) {}

    private final LocalStore store;
    private final AppProperties properties;

    public DocumentParser(LocalStore store, AppProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public ParseResult parse(UUID packageId, List<SourceItem> items) {
        List<ContentBlock> blocks = new ArrayList<>();
        List<VisionInput> visionInputs = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        for (SourceItem item : items) {
            switch (item.kind()) {
                case PASTED_TEXT, TEXT_FILE -> blocks.addAll(parseText(packageId, item, sequence));
                case PDF -> parsePdf(packageId, item, sequence, blocks, visionInputs);
                case IMAGE -> visionInputs.add(new VisionInput(item.id(), item.assetId(), item.originalName()));
                case VIDEO -> { }
            }
        }
        return new ParseResult(blocks, visionInputs);
    }

    private List<ContentBlock> parseText(UUID packageId, SourceItem item, AtomicInteger sequence) {
        Path path = store.resolveAsset(packageId, item.assetId());
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8)
                    .replace("\uFEFF", "")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("\\n{3,}", "\n\n");
            String[] paragraphs = raw.split("\\n\\s*\\n");
            List<ContentBlock> blocks = new ArrayList<>();
            int paragraphNumber = 0;
            for (String paragraph : paragraphs) {
                String content = paragraph.strip();
                if (content.isBlank()) continue;
                paragraphNumber++;
                BlockType type = content.startsWith("#") ? BlockType.HEADING
                        : content.startsWith("```") ? BlockType.CODE : BlockType.TEXT;
                for (String chunk : chunk(content, 4000)) {
                    blocks.add(new ContentBlock(blockId(), packageId, item.id(), type, sequence.getAndIncrement(),
                            chunk, new SourceRef(SourceRefKind.TEXT_PARAGRAPH, null, paragraphNumber,
                            item.assetId(), null, null), 1.0, Map.of()));
                }
            }
            return blocks;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_DECODE_FAILED",
                    "文本文件必须使用 UTF-8 编码。", false);
        }
    }

    private void parsePdf(UUID packageId, SourceItem item, AtomicInteger sequence, List<ContentBlock> blocks,
                          List<VisionInput> visionInputs) {
        Path path = store.resolveAsset(packageId, item.assetId());
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_ENCRYPTED",
                        "PDF 已加密或禁止提取内容。", false);
            }
            if (document.getNumberOfPages() > properties.upload().maxPdfPages()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_PAGE_LIMIT_EXCEEDED",
                        "PDF 页数超过限制。", false);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<Integer> visualCandidates = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                if (text.replaceAll("\\s", "").length() < 80) {
                    visualCandidates.add(page);
                }
                if (!text.isBlank()) {
                    for (String paragraph : text.split("\\n\\s*\\n")) {
                        if (!paragraph.isBlank()) {
                            blocks.add(new ContentBlock(blockId(), packageId, item.id(), BlockType.TEXT,
                                    sequence.getAndIncrement(), paragraph.strip(),
                                    new SourceRef(SourceRefKind.PDF_PAGE, page, null, item.assetId(), null, null),
                                    1.0, Map.of("fileName", item.originalName())));
                        }
                    }
                }
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            for (int page : visualCandidates.stream().limit(properties.pdf().visualPageLimit()).toList()) {
                var image = renderer.renderImageWithDPI(page - 1, properties.pdf().renderDpi(), ImageType.RGB);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", output);
                StoredAsset rendered = store.storeBytes(packageId, "page-" + page + ".jpg", "image/jpeg",
                        output.toByteArray(), "extracted");
                visionInputs.add(new VisionInput(item.id(), rendered.id(), item.originalName() + " 第 " + page + " 页"));
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_CORRUPTED",
                    "PDF 损坏或无法读取。", false);
        }
    }

    private List<String> chunk(String value, int size) {
        if (value.length() <= size) return List.of(value);
        List<String> chunks = new ArrayList<>();
        for (int offset = 0; offset < value.length(); offset += size) {
            chunks.add(value.substring(offset, Math.min(value.length(), offset + size)));
        }
        return chunks;
    }

    private String blockId() {
        return "blk_" + UUID.randomUUID().toString().replace("-", "");
    }
}

