package com.smallrag.ragmcp.rag.archive;

import com.smallrag.ragmcp.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于文件系统的原始文档归档。
 *
 * <p>每个文档一个 JSON 文件，写入采用“临时文件 + 原子替换”，避免进程崩溃留下半截文件。
 * 归档只服务于自动重切：Qdrant 仍然是唯一的向量库，归档不参与检索。
 *
 * <p>另维护一份轻量摘要索引（{@code .archive/index.json}），避免 {@code rag_list}
 * 和启动重切每次都读取全部原文 JSON；索引缺失、版本不符或文档 id 集合不一致时自动重建。
 *
 * <p>目录不可写时降级为禁用（只告警，不阻断入库），此时自动重切不可用。
 *
 * @author zjk
 */
@Component
@Slf4j
public class DocumentArchive {

    private static final int INDEX_VERSION = 1;
    private static final String INDEX_DIRECTORY = ".archive";
    private static final String INDEX_FILE = "index.json";

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Path indexFile;
    private volatile boolean enabled;
    private final Map<String, ArchivedDocumentSummary> summaries = new LinkedHashMap<>();
    private boolean indexLoaded;

    public DocumentArchive(ObjectMapper objectMapper, RagProperties properties) {
        this.objectMapper = objectMapper;
        this.directory = Paths.get(properties.getArchiveDir()).toAbsolutePath().normalize();
        this.indexFile = directory.resolve(INDEX_DIRECTORY).resolve(INDEX_FILE);
        try {
            Files.createDirectories(directory);
            // 仅创建目录不代表可写（例如只读挂载已存在目录），做一次真实写入探测
            Path probe = Files.createTempFile(directory, ".write-test", ".tmp");
            Files.deleteIfExists(probe);
            this.enabled = true;
            log.info("原始文档归档目录: {}", directory);
        } catch (IOException e) {
            this.enabled = false;
            log.warn("无法创建原始文档归档目录，自动重切将不可用: {} ({})", directory, e.toString());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 归档目录路径（用于健康检查与诊断）。
     *
     * @return 绝对规范化路径
     */
    public Path directory() {
        return directory;
    }

    /**
     * 探测归档目录当前是否可写。
     *
     * <p>只读挂载下 {@link Files#isWritable(Path)} 可能仍返回 true，因此做一次真实写入探测。
     *
     * @return true 表示可写
     */
    public boolean isWritable() {
        if (!enabled) {
            return false;
        }
        try {
            Path probe = Files.createTempFile(directory, ".health-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 保存或覆盖一篇原始文档。
     */
    public synchronized void save(String documentId, String text, Map<String, String> metadata, String chunkSignature) {
        if (!enabled) {
            return;
        }
        ensureIndex();
        ArchivedDocument document = new ArchivedDocument(
                documentId, text, metadata, chunkSignature, Instant.now().toString());
        write(document);
        summaries.put(documentId, ArchivedDocumentSummary.of(document));
        persistIndex();
    }

    /**
     * 读取全部归档文档（含全文）；批量重建请优先使用 {@link #listSummaries()} + {@link #find(String)}。
     */
    public synchronized List<ArchivedDocument> listAll() {
        if (!enabled || !Files.isDirectory(directory)) {
            return List.of();
        }

        List<ArchivedDocument> documents = new ArrayList<>();
        for (Path path : documentFiles()) {
            ArchivedDocument document = readDocument(path);
            if (document != null) {
                documents.add(document);
            }
        }
        documents.sort(Comparator.comparing(ArchivedDocument::documentId));
        return documents;
    }

    /**
     * 读取全部归档文档的轻量摘要；不加载原文。
     *
     * @return 按文档 id 升序排列的摘要
     */
    public synchronized List<ArchivedDocumentSummary> listSummaries() {
        if (!enabled) {
            return List.of();
        }
        ensureIndex();
        return summaries.values().stream()
                .sorted(Comparator.comparing(ArchivedDocumentSummary::documentId))
                .toList();
    }

    /**
     * 按文档 id 读取归档记录。
     */
    public synchronized Optional<ArchivedDocument> find(String documentId) {
        if (!enabled) {
            return Optional.empty();
        }
        Path path = fileFor(documentId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.ofNullable(readDocument(path));
    }

    /**
     * 更新文档最近一次成功切分时的配置签名。
     */
    public synchronized void markIndexed(String documentId, String chunkSignature) {
        if (!enabled) {
            return;
        }
        ensureIndex();
        Path path = fileFor(documentId);
        if (!Files.isRegularFile(path)) {
            log.warn("归档文档不存在，无法更新切分签名: {}", documentId);
            return;
        }
        try {
            ArchivedDocument current = objectMapper.readValue(path, ArchivedDocument.class);
            ArchivedDocument updated = new ArchivedDocument(
                    current.documentId(),
                    current.text(),
                    current.metadata(),
                    chunkSignature,
                    Instant.now().toString());
            write(updated);
            summaries.put(documentId, ArchivedDocumentSummary.of(updated));
            persistIndex();
        } catch (Exception e) {
            throw new IllegalStateException("更新归档文档切分签名失败: " + documentId, e);
        }
    }

    /**
     * 删除归档；入库失败时用于回滚。
     */
    public synchronized void delete(String documentId) {
        if (!enabled) {
            return;
        }
        ensureIndex();
        try {
            Files.deleteIfExists(fileFor(documentId));
            summaries.remove(documentId);
            persistIndex();
        } catch (Exception e) {
            log.warn("删除归档文档失败: docId={}, 原因={}", documentId, e.toString());
        }
    }

    private void ensureIndex() {
        if (indexLoaded) {
            return;
        }
        // 先置位，避免 rebuild 过程中递归调用
        indexLoaded = true;
        IndexFile loaded = loadIndexFile();
        Set<String> actualIds = documentFiles().stream()
                .map(path -> path.getFileName().toString())
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .collect(Collectors.toSet());
        if (loaded == null || !loaded.documents().keySet().equals(actualIds)) {
            summaries.clear();
            for (Path path : documentFiles()) {
                ArchivedDocument document = readDocument(path);
                if (document != null) {
                    summaries.put(document.documentId(), ArchivedDocumentSummary.of(document));
                }
            }
            persistIndex();
            log.info("重建归档摘要索引: {} 篇", summaries.size());
        } else {
            summaries.clear();
            summaries.putAll(loaded.documents());
        }
    }

    private IndexFile loadIndexFile() {
        if (!Files.isRegularFile(indexFile)) {
            return null;
        }
        try {
            IndexFile index = objectMapper.readValue(indexFile, IndexFile.class);
            if (index == null || index.version() != INDEX_VERSION || index.documents() == null) {
                return null;
            }
            return index;
        } catch (Exception e) {
            log.warn("读取归档摘要索引失败，将重建: {} ({})", indexFile, e.toString());
            return null;
        }
    }

    private void persistIndex() {
        if (!enabled) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(indexFile.getParent());
            temporary = Files.createTempFile(indexFile.getParent(), "archive-index", ".tmp");
            objectMapper.writeValue(temporary, new IndexFile(INDEX_VERSION, new LinkedHashMap<>(summaries)));
            try {
                Files.move(temporary, indexFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("写入归档摘要索引失败，下次读取将重建: {} ({})", indexFile, e.toString());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.debug("清理归档摘要索引临时文件失败: {}", temporary, e);
                }
            }
        }
    }

    private List<Path> documentFiles() {
        if (!enabled || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(DocumentArchive::isDocumentFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("读取原始文档归档目录失败: {} ({})", directory, e.toString());
            return List.of();
        }
    }

    private static boolean isDocumentFile(Path path) {
        String name = path.getFileName().toString();
        try {
            UUID.fromString(name.substring(0, name.length() - ".json".length()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ArchivedDocument readDocument(Path path) {
        try {
            ArchivedDocument document = objectMapper.readValue(path, ArchivedDocument.class);
            if (document.documentId() == null
                    || document.text() == null
                    || document.text().isBlank()) {
                log.warn("跳过不完整的归档文件: {}", path);
                return null;
            }
            return document;
        } catch (Exception e) {
            log.warn("跳过损坏的归档文件: {} ({})", path, e.toString());
            return null;
        }
    }

    private void write(ArchivedDocument document) {
        Path target = fileFor(document.documentId());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, document.documentId(), ".json.tmp");
            objectMapper.writeValue(temporary, document);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入归档文档失败: " + document.documentId(), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.debug("清理归档临时文件失败: {}", temporary, e);
                }
            }
        }
    }

    private Path fileFor(String documentId) {
        try {
            UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法文档 id: " + documentId, e);
        }
        return directory.resolve(documentId + ".json");
    }

    /**
     * 摘要索引文件结构。
     */
    private record IndexFile(int version, Map<String, ArchivedDocumentSummary> documents) {
    }
}
