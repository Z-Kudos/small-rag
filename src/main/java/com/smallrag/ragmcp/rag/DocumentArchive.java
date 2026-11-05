package com.smallrag.ragmcp.rag;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于文件系统的原始文档归档。
 *
 * <p>每个文档一个 JSON 文件，写入采用“临时文件 + 原子替换”，避免进程崩溃留下半截文件。
 * 归档只服务于自动重切：Qdrant 仍然是唯一的向量库，归档不参与检索。
 *
 * <p>目录不可写时降级为禁用（只告警，不阻断入库），此时自动重切不可用。
 */
@Component
@Slf4j
public class DocumentArchive {

    private final ObjectMapper objectMapper;
    private final Path directory;
    private volatile boolean enabled;

    public DocumentArchive(ObjectMapper objectMapper, RagProperties properties) {
        this.objectMapper = objectMapper;
        this.directory = Paths.get(properties.getArchiveDir()).toAbsolutePath().normalize();
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
     * 保存或覆盖一篇原始文档。
     */
    public synchronized void save(String documentId, String text, Map<String, String> metadata, String chunkSignature) {
        if (!enabled) {
            return;
        }
        write(new ArchivedDocument(documentId, text, metadata, chunkSignature, Instant.now().toString()));
    }

    /**
     * 读取全部归档文档；单篇损坏只告警并跳过。
     */
    public synchronized List<ArchivedDocument> listAll() {
        if (!enabled || !Files.isDirectory(directory)) {
            return List.of();
        }

        List<ArchivedDocument> documents = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            ArchivedDocument document = objectMapper.readValue(path, ArchivedDocument.class);
                            if (document.documentId() == null
                                    || document.text() == null
                                    || document.text().isBlank()) {
                                log.warn("跳过不完整的归档文件: {}", path);
                                return;
                            }
                            documents.add(document);
                        } catch (Exception e) {
                            log.warn("跳过损坏的归档文件: {} ({})", path, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("读取原始文档归档目录失败: {} ({})", directory, e.toString());
            return List.of();
        }
        documents.sort(Comparator.comparing(ArchivedDocument::documentId));
        return documents;
    }

    /**
     * 更新文档最近一次成功切分时的配置签名。
     */
    public synchronized void markIndexed(String documentId, String chunkSignature) {
        if (!enabled) {
            return;
        }
        Path path = fileFor(documentId);
        if (!Files.isRegularFile(path)) {
            log.warn("归档文档不存在，无法更新切分签名: {}", documentId);
            return;
        }
        try {
            ArchivedDocument current = objectMapper.readValue(path, ArchivedDocument.class);
            write(new ArchivedDocument(
                    current.documentId(),
                    current.text(),
                    current.metadata(),
                    chunkSignature,
                    Instant.now().toString()));
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
        try {
            Files.deleteIfExists(fileFor(documentId));
        } catch (Exception e) {
            log.warn("删除归档文档失败: docId={}, 原因={}", documentId, e.toString());
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
}
