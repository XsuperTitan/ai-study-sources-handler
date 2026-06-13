package com.aisourceshandler.api;

import com.aisourceshandler.rag.RagService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

import static com.aisourceshandler.rag.RagModels.*;

@RestController
@RequestMapping("/api/v1")
public class RagController {
    private final RagService rag;

    public RagController(RagService rag) {
        this.rag = rag;
    }

    @GetMapping("/rag/status")
    RagStatus status() {
        return rag.status();
    }

    @PostMapping("/rag/ask")
    RagAnswer ask(@RequestBody RagAskRequest request) {
        return rag.ask(request);
    }

    @PostMapping("/rag/reindex-all")
    ReindexResult reindexAll() {
        return rag.reindexAll();
    }

    @PostMapping("/packages/{packageId}/rag/reindex")
    Map<String, Object> reindexPackage(@PathVariable UUID packageId) {
        return Map.of("packageId", packageId, "chunksIndexed", rag.indexPackage(packageId));
    }
}
