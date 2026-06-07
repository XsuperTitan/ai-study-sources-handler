package com.aisourceshandler.infrastructure;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Component
public class LocalStore {
    private final Path root;
    private final ObjectMapper mapper;
    private final Map<UUID, ReentrantLock> packageLocks = new ConcurrentHashMap<>();

    public LocalStore(Path storageRoot, ObjectMapper mapper) {
        this.root = storageRoot;
        this.mapper = mapper;
        try {
            Files.createDirectories(root.resolve("packages"));
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建运行数据目录", exception);
        }
    }

    public Path packageRoot(UUID packageId) {
        return guarded(root.resolve("packages").resolve(packageId.toString()));
    }

    public SourcePackage savePackage(SourcePackage sourcePackage) {
        withLock(sourcePackage.id(), () -> writeJson(packageRoot(sourcePackage.id()).resolve("package.json"), sourcePackage));
        return sourcePackage;
    }

    public Optional<SourcePackage> findPackage(UUID id) {
        return readJson(packageRoot(id).resolve("package.json"), SourcePackage.class);
    }

    public void deletePackage(UUID packageId) {
        Path target = packageRoot(packageId);
        if (!Files.exists(target)) {
            return;
        }
        withLock(packageId, () -> {
            try (Stream<Path> paths = Files.walk(target)) {
                List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
                for (Path path : ordered) {
                    Files.deleteIfExists(guarded(path));
                }
            } catch (IOException exception) {
                throw storageError(exception);
            }
        });
    }

    public List<SourcePackage> findAllPackages() {
        Path packages = root.resolve("packages");
        if (!Files.exists(packages)) return List.of();
        try (Stream<Path> paths = Files.list(packages)) {
            return paths.map(path -> readJson(path.resolve("package.json"), SourcePackage.class))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(SourcePackage::createdAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    public void saveSourceItems(UUID packageId, List<SourceItem> items) {
        withLock(packageId, () -> writeJson(packageRoot(packageId).resolve("source-items.json"), items));
    }

    public List<SourceItem> sourceItems(UUID packageId) {
        return readJson(packageRoot(packageId).resolve("source-items.json"),
                new TypeReference<List<SourceItem>>() {}).orElse(List.of());
    }

    public StoredAsset storeUpload(UUID packageId, MultipartFile file) {
        String originalName = Optional.ofNullable(file.getOriginalFilename()).orElse("upload");
        String extension = safeExtension(originalName);
        UUID assetId = UUID.randomUUID();
        Path target = guarded(packageRoot(packageId).resolve("assets/original").resolve(assetId + extension));
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            StoredAsset asset = new StoredAsset(assetId, originalName,
                    Optional.ofNullable(file.getContentType()).orElse("application/octet-stream"),
                    file.getSize(), root.relativize(target).toString().replace('\\', '/'));
            saveAsset(packageId, asset);
            return asset;
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    public StoredAsset storeBytes(UUID packageId, String originalName, String contentType, byte[] bytes, String bucket) {
        UUID assetId = UUID.randomUUID();
        String extension = safeExtension(originalName);
        Path target = guarded(packageRoot(packageId).resolve("assets").resolve(bucket).resolve(assetId + extension));
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            StoredAsset asset = new StoredAsset(assetId, originalName, contentType, bytes.length,
                    root.relativize(target).toString().replace('\\', '/'));
            saveAsset(packageId, asset);
            return asset;
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    public Optional<StoredAsset> asset(UUID packageId, UUID assetId) {
        return assets(packageId).stream().filter(asset -> asset.id().equals(assetId)).findFirst();
    }

    public Path resolveAsset(UUID packageId, UUID assetId) {
        StoredAsset asset = asset(packageId, assetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "资源不存在。", false));
        return guarded(root.resolve(asset.relativePath()));
    }

    public void saveContentBlocks(UUID packageId, List<ContentBlock> blocks) {
        withLock(packageId, () -> writeJson(packageRoot(packageId).resolve("normalized/content-blocks.json"), blocks));
    }

    public List<ContentBlock> contentBlocks(UUID packageId) {
        return readJson(packageRoot(packageId).resolve("normalized/content-blocks.json"),
                new TypeReference<List<ContentBlock>>() {})
                .orElse(List.of());
    }

    public ProcessingJob saveJob(ProcessingJob job) {
        withLock(job.packageId(), () -> writeJson(packageRoot(job.packageId()).resolve("jobs/" + job.id() + ".json"), job));
        return job;
    }

    public Optional<ProcessingJob> findJob(UUID jobId) {
        for (SourcePackage sourcePackage : findAllPackages()) {
            Optional<ProcessingJob> found = readJson(packageRoot(sourcePackage.id()).resolve("jobs/" + jobId + ".json"),
                    ProcessingJob.class);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    public List<ProcessingJob> jobs(UUID packageId) {
        Path directory = packageRoot(packageId).resolve("jobs");
        if (!Files.exists(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.toString().endsWith(".json"))
                    .map(path -> readJson(path, ProcessingJob.class))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(ProcessingJob::startedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .toList();
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    public void writeText(UUID packageId, String relative, String content) {
        writeBytes(packageId, relative, content.getBytes(StandardCharsets.UTF_8));
    }

    public String readText(UUID packageId, String relative) {
        Path path = guarded(packageRoot(packageId).resolve(relative));
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "OUTPUT_NOT_READY", "结果尚未生成。", true);
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    public void writeJsonOutput(UUID packageId, String relative, Object value) {
        withLock(packageId, () -> writeJson(packageRoot(packageId).resolve(relative), value));
    }

    public <T> Optional<T> readJsonOutput(UUID packageId, String relative, Class<T> type) {
        return readJson(packageRoot(packageId).resolve(relative), type);
    }

    private void writeBytes(UUID packageId, String relative, byte[] bytes) {
        Path path = guarded(packageRoot(packageId).resolve(relative));
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    private void saveAsset(UUID packageId, StoredAsset asset) {
        withLock(packageId, () -> {
            List<StoredAsset> current = new ArrayList<>(assets(packageId));
            current.removeIf(existing -> existing.id().equals(asset.id()));
            current.add(asset);
            writeJson(packageRoot(packageId).resolve("assets/index.json"), current);
        });
    }

    private List<StoredAsset> assets(UUID packageId) {
        return readJson(packageRoot(packageId).resolve("assets/index.json"),
                new TypeReference<List<StoredAsset>>() {}).orElse(List.of());
    }

    private <T> Optional<T> readJson(Path path, Class<T> type) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(path.toFile(), type));
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    private <T> Optional<T> readJson(Path path, TypeReference<T> type) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(path.toFile(), type));
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw storageError(exception);
        }
    }

    private void withLock(UUID packageId, Runnable action) {
        ReentrantLock lock = packageLocks.computeIfAbsent(packageId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private Path guarded(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_PATH", "非法资源路径。", false);
        }
        return normalized;
    }

    private String safeExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }

    private ApiException storageError(Exception exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED",
                "本地存储读写失败。", true);
    }
}
