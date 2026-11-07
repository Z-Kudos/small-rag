package com.smallrag.ragmcp.rag.sources;

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
import java.util.Map;

/**
 * 目录导入的增量状态：记录每个源文件的指纹与对应文档 id。
 *
 * <p>状态文件默认位于 {@code <archive-dir>/.sources/state.json}，与归档同卷；
 * 使用“临时文件 + 原子替换”写入。文件丢失或损坏时会从归档元数据重建，
 * 最坏情况只是多一次（幂等的）重新索引。
 *
 * @author zjk
 */
@Component
@Slf4j
public class SourceStateStore {

    /**
     * 单个源文件的已导入状态。
     *
     * @param documentId     文档 id
     * @param root           源根目录（绝对路径，规范化）
     * @param file           相对源根目录的文件路径（使用 {@code /}）
     * @param size           文件字节数
     * @param modifiedMillis 文件 mtime（毫秒）
     * @param sha256         文件内容 SHA-256（十六进制）
     * @param indexedAt      最近一次成功导入时间（ISO-8601）
     */
    public record FileState(
            String documentId,
            String root,
            String file,
            long size,
            long modifiedMillis,
            String sha256,
            String indexedAt) {
    }

    /**
     * 全部源文件状态；key 为 {@code root::file}。
     *
     * @param files 源文件状态
     */
    public record State(Map<String, FileState> files) {

        public State {
            files = files == null ? Map.of() : Map.copyOf(files);
        }

        public static State empty() {
            return new State(Map.of());
        }
    }

    /**
     * 读取结果：区分“文件不存在（需要从归档重建）”与“存在但为空”。
     *
     * @param state   状态
     * @param present 状态文件是否存在且可读
     */
    public record Snapshot(State state, boolean present) {
    }

    private final ObjectMapper objectMapper;
    private final Path path;

    public SourceStateStore(ObjectMapper objectMapper, RagProperties properties) {
        this.objectMapper = objectMapper;
        this.path = resolvePath(properties);
    }

    /**
     * @return 状态文件路径
     */
    public Path path() {
        return path;
    }

    /**
     * 读取状态；文件不存在或损坏时返回 {@code present=false}。
     *
     * @return 状态快照
     */
    public synchronized Snapshot load() {
        if (!Files.isRegularFile(path)) {
            return new Snapshot(State.empty(), false);
        }
        try {
            State state = objectMapper.readValue(path, State.class);
            if (state == null) {
                return new Snapshot(State.empty(), false);
            }
            return new Snapshot(state, true);
        } catch (Exception e) {
            log.warn("读取目录导入状态失败，将从归档重建: {} ({})", path, e.toString());
            return new Snapshot(State.empty(), false);
        }
    }

    /**
     * 原子写入状态；失败抛运行时异常。
     *
     * @param state 状态
     */
    public synchronized void save(State state) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "sources-state", ".tmp");
            objectMapper.writeValue(temporary, state);
            try {
                Files.move(temporary, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入目录导入状态失败: " + path, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.debug("清理目录导入状态临时文件失败: {}", temporary, e);
                }
            }
        }
    }

    private static Path resolvePath(RagProperties properties) {
        String configured = properties.getSources().getStateFile();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(properties.getArchiveDir())
                .toAbsolutePath()
                .normalize()
                .resolve(".sources")
                .resolve("state.json");
    }
}
