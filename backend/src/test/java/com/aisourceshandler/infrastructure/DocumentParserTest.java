package com.aisourceshandler.infrastructure;

import com.aisourceshandler.TestProperties;
import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserTest {
    @TempDir
    Path temp;

    @Test
    void parsesHeadingsAndParagraphsWithSourceLocation() {
        LocalStore store = new LocalStore(temp, new ObjectMapper().registerModule(new JavaTimeModule()));
        UUID packageId = UUID.randomUUID();
        StoredAsset asset = store.storeBytes(packageId, "notes.md", "text/markdown",
                "# Thread Pool\n\nReuse worker threads.\n\n```java\nrun();\n```".getBytes(StandardCharsets.UTF_8),
                "original");
        SourceItem item = new SourceItem(UUID.randomUUID(), packageId, SourceKind.TEXT_FILE,
                "notes.md", asset.id(), 0, Map.of());
        DocumentParser parser = new DocumentParser(store, TestProperties.create(temp.toString(), "http://localhost"));

        DocumentParser.ParseResult result = parser.parse(packageId, List.of(item));

        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks().getFirst().type()).isEqualTo(BlockType.HEADING);
        assertThat(result.blocks()).allSatisfy(block ->
                assertThat(block.sourceRef().kind()).isEqualTo(SourceRefKind.TEXT_PARAGRAPH));
    }

    @Test
    void parsesPdfTextPagesWithPageSourceLocation() throws IOException {
        LocalStore store = store();
        UUID packageId = UUID.randomUUID();
        StoredAsset asset = store.storeBytes(packageId, "thread-pools.pdf", "application/pdf",
                pdfWithText("Thread pools reuse worker threads across queued tasks. ".repeat(4)), "original");
        DocumentParser parser = parser(store, TestProperties.create(temp.toString(), "http://localhost"));

        DocumentParser.ParseResult result = parser.parse(packageId, List.of(pdfItem(packageId, asset)));

        assertThat(result.blocks()).isNotEmpty();
        assertThat(result.blocks().getFirst().sourceRef().kind()).isEqualTo(SourceRefKind.PDF_PAGE);
        assertThat(result.blocks().getFirst().sourceRef().pageNumber()).isEqualTo(1);
    }

    @Test
    void rendersLowTextPdfPagesAsVisionCandidates() throws IOException {
        LocalStore store = store();
        UUID packageId = UUID.randomUUID();
        StoredAsset asset = store.storeBytes(packageId, "scan.pdf", "application/pdf",
                blankPdf(1), "original");
        DocumentParser parser = parser(store, TestProperties.create(temp.toString(), "http://localhost"));

        DocumentParser.ParseResult result = parser.parse(packageId, List.of(pdfItem(packageId, asset)));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.visionInputs()).hasSize(1);
        assertThat(store.asset(packageId, result.visionInputs().getFirst().assetId())).isPresent();
    }

    @Test
    void rejectsEncryptedPdfWithoutExtractPermission() throws IOException {
        LocalStore store = store();
        UUID packageId = UUID.randomUUID();
        StoredAsset asset = store.storeBytes(packageId, "locked.pdf", "application/pdf",
                encryptedPdfWithoutExtractPermission(), "original");
        DocumentParser parser = parser(store, TestProperties.create(temp.toString(), "http://localhost"));

        assertThatThrownBy(() -> parser.parse(packageId, List.of(pdfItem(packageId, asset))))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("PDF_ENCRYPTED");
    }

    @Test
    void rejectsCorruptedPdf() {
        LocalStore store = store();
        UUID packageId = UUID.randomUUID();
        StoredAsset asset = store.storeBytes(packageId, "broken.pdf", "application/pdf",
                "%PDF- broken".getBytes(StandardCharsets.UTF_8), "original");
        DocumentParser parser = parser(store, TestProperties.create(temp.toString(), "http://localhost"));

        assertThatThrownBy(() -> parser.parse(packageId, List.of(pdfItem(packageId, asset))))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("PDF_CORRUPTED");
    }

    @Test
    void rejectsPdfOverPageLimit() throws IOException {
        LocalStore store = store();
        UUID packageId = UUID.randomUUID();
        StoredAsset asset = store.storeBytes(packageId, "long.pdf", "application/pdf",
                blankPdf(2), "original");
        DocumentParser parser = parser(store, propertiesWithPdfPageLimit(1));

        assertThatThrownBy(() -> parser.parse(packageId, List.of(pdfItem(packageId, asset))))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("PDF_PAGE_LIMIT_EXCEEDED");
    }

    private LocalStore store() {
        return new LocalStore(temp, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private DocumentParser parser(LocalStore store, AppProperties properties) {
        return new DocumentParser(store, properties);
    }

    private SourceItem pdfItem(UUID packageId, StoredAsset asset) {
        return new SourceItem(UUID.randomUUID(), packageId, SourceKind.PDF,
                asset.originalName(), asset.id(), 0, Map.of());
    }

    private byte[] pdfWithText(String text) throws IOException {
        Path file = Files.createTempFile(temp, "text", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(file.toFile());
        }
        return Files.readAllBytes(file);
    }

    private byte[] blankPdf(int pages) throws IOException {
        Path file = Files.createTempFile(temp, "blank", ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage());
            }
            document.save(file.toFile());
        }
        return Files.readAllBytes(file);
    }

    private byte[] encryptedPdfWithoutExtractPermission() throws IOException {
        byte[] plain = pdfWithText("Locked PDF content");
        Path plainFile = Files.createTempFile(temp, "plain", ".pdf");
        Files.write(plainFile, plain);
        Path encryptedFile = Files.createTempFile(temp, "locked", ".pdf");
        try (PDDocument document = Loader.loadPDF(plainFile.toFile())) {
            AccessPermission permission = new AccessPermission();
            permission.setCanExtractContent(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-password", "", permission);
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(encryptedFile.toFile());
        }
        return Files.readAllBytes(encryptedFile);
    }

    private AppProperties propertiesWithPdfPageLimit(int maxPdfPages) {
        return new AppProperties(
                false,
                temp.toString(),
                new AppProperties.Upload(20, 104857600, 10485760, 2097152, maxPdfPages),
                new AppProperties.Pdf(12, 144),
                new AppProperties.Jobs(1, 2, 8),
                new AppProperties.Provider("test-key", "http://localhost", "deepseek-chat"),
                new AppProperties.Provider("test-key", "http://localhost", "qwen-vl-max"),
                new AppProperties.Provider("", "http://localhost", "wanx"),
                new AppProperties.Video("yt-dlp", 5, "")
        );
    }
}
