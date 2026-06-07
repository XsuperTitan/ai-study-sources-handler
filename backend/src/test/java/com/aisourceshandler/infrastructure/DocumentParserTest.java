package com.aisourceshandler.infrastructure;

import com.aisourceshandler.TestProperties;
import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
}

