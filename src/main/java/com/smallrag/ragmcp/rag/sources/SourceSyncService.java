package com.smallrag.ragmcp.rag.sources;

import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 多目录 Markdown 源文件的自动导入。
 *
 * <p>扫描 {@code rag.sources.directories} 下的 Markdown 文件，按“根目录 + 相对路径”
 * 生成确定性文档 id，调用 {@link RagService#upsert} 完成解析、切分、Embedding 与归档。
 * 同一文件重复扫描不会产生重复文档；内容未变化时直接跳过。
 *
 * <p>触发方式：
 * <ul>
 *   <li>启动后扫描（{@code scan-on-startup}）；</li>
 *   <li>定时扫描（{@code scan-interval-seconds}，{@code <= 0} 关闭）；</li>
 *   <li>MCP {@code rag_sync_sources} 手动触发（可 {@code force=true} 忽略指纹）；</li>
 *   <li>{@code max-files-per-scan} / {@code max-file-bytes} 限制单轮工作量。</li>
 * </ul>
 *
 * <p>增量判断：先比 mtime + size，再比 SHA-256；只有内容变化才重新切分。
 * 状态文件丢失时从归档元数据重建；最坏情况是多一次幂等重建。
 *
 * <p>与重建任务通过 {@link IndexActivityGuard} 互斥：重建运行中不做目录导入，
 * 目录导入运行中也不接受新的重建任务。
 *
 * @author zjk
 */
@Service
@Slf4j
public class SourceSyncService {

    static final String ACTIVITY_LABEL = IndexActivityGuard.SOURCES;
    static final String META_SOURCE_KEY = "source_key";
    static final String META_SOURCE_ROOT = "source_root";
    static final String META_SOURCE_FILE = "source_file";
    static final String META_SOURCE_PATH = "source_path";
    static final String META_SOURCE_SIZE = "source_size";
    static final String META_SOURCE_MTIME = "source_mtime";
    static final String META_SOURCE_SHA256 = "source_sha256";

    private final RagService ragService;
    private final DocumentArchive documentArchive;
    private final RagProperties properties;
    private final SourceStateStore stateStore;
    private final IndexActivityGuard activity;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile Summary lastRun;
    private volatile String lastError;

    public SourceSyncService(RagService ragService, DocumentArchive documentArchive,
            RagProperties properties, SourceStateStore stateStore, IndexActivityGuard activity) {
        this.ragService = ragService;
        this.documentArchive = documentArchive;
        this.properties = properties;
        this.stateStore = stateStore;
        this.activity = activity;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rag-source-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * @return 是否启用目录导入
     */
    public boolean isEnabled() {
        RagProperties.Sources sources = properties.getSources();
        return sources.isEnabled() && !sources.directoryList().isEmpty();
    }

    /**
     * @return 当前状态与最近一次扫描统计
     */
    public Status status() {
        RagProperties.Sources sources = properties.getSources();
        return new Status(isEnabled(), running, sources.directoryList(),
                sources.getScanIntervalSeconds(), lastError, lastRun);
    }

    /**
     * 立即执行一次增量扫描（手动触发）。
     *
     * <p>可能耗时较长（首次导入需要 Embedding）；大批量首次导入建议依赖定时任务。
     *
     * @param force true 时忽略文件指纹，重新切分并建立索引
     * @return 本次扫描统计
     * @throws IllegalStateException 目录导入未启用、归档不可用或有其他索引任务在运行
     */
    public Summary sync(boolean force) {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "目录导入未启用（rag.sources.enabled=false 或未配置 rag.sources.directories）");
        }
        if (!documentArchive.isEnabled()) {
            throw new IllegalStateException("原始文档归档不可用，无法执行目录导入");
        }
        if (!activity.tryAcquire(ACTIVITY_LABEL)) {
            throw new IllegalStateException("其他索引任务正在执行，请稍后重试: "
                    + activity.owner().orElse("unknown"));
        }

        running = true;
        lastError = null;
        String startedAt = Instant.now().toString();
        long startedNanos = System.nanoTime();
        try {
            Summary summary = doSync(force, startedAt, startedNanos);
            lastRun = summary;
            return summary;
        } catch (RuntimeException e) {
            lastError = rootMessage(e);
            throw e;
        } finally {
            running = false;
            activity.release(ACTIVITY_LABEL);
            Thread.interrupted();
        }
    }

    /**
     * 从增量状态中移除指定文档（MCP 手动删除导入文档后调用）。
     *
     * <p>源文件仍然存在时，下一次扫描会把它当作新文件重新导入；源文件已删除时不会。
     *
     * @param documentId 文档 id
     */
    public void removeFromState(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        try {
            SourceStateStore.Snapshot snapshot = stateStore.load();
            if (!snapshot.present()) {
                return;
            }
            Map<String, SourceStateStore.FileState> files =
                    new LinkedHashMap<>(snapshot.state().files());
            boolean removed = files.values().removeIf(state -> documentId.equals(state.documentId()));
            if (removed) {
                stateStore.save(new SourceStateStore.State(files));
            }
        } catch (Exception e) {
            log.warn("从目录导入状态移除文档失败: docId={}, 原因={}", documentId, e.toString());
        }
    }

    /**
     * 应用就绪后启动首次扫描与定时扫描；排在重建任务自动续接之后。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        if (!isEnabled()) {
            log.info("目录导入未启用");
            return;
        }
        if (!documentArchive.isEnabled()) {
            log.warn("原始文档归档不可用，目录导入不会自动启动");
            return;
        }
        RagProperties.Sources sources = properties.getSources();
        if (sources.isScanOnStartup()) {
            scheduler.execute(() -> runScheduled("启动"));
        }
        if (sources.getScanIntervalSeconds() > 0) {
            long interval = sources.getScanIntervalSeconds();
            scheduler.scheduleWithFixedDelay(
                    () -> runScheduled("定时"), interval, interval, TimeUnit.SECONDS);
            log.info("目录导入已启用: {} 个目录, 每 {} 秒扫描一次",
                    sources.directoryList().size(), interval);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void runScheduled(String trigger) {
        try {
            Summary summary = sync(false);
            log.info("{}目录导入完成: {}", trigger, summary);
        } catch (Exception e) {
            log.warn("{}目录导入失败: {}", trigger, e.toString());
        }
    }

    private Summary doSync(boolean force, String startedAt, long startedNanos) {
        RagProperties.Sources sources = properties.getSources();
        List<Root> roots = resolveRoots(sources);
        List<Candidate> candidates = new ArrayList<>();
        Set<Path> seenPaths = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        Set<String> scannedRoots = new HashSet<>();
        Set<String> partialRoots = new HashSet<>();

        for (Root root : roots) {
            if (!Files.isDirectory(root.path())) {
                log.warn("源目录不存在或不是目录，跳过: {}", root.path());
                continue;
            }
            scannedRoots.add(root.key());
            walk(root, sources, candidates, seenPaths, seenKeys, partialRoots);
        }
        candidates.sort(Comparator.comparing(Candidate::key));

        Map<String, SourceStateStore.FileState> files =
                new LinkedHashMap<>(loadState().state().files());
        int indexed = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        int deleted = 0;
        int processed = 0;
        boolean truncated = false;
        int limit = force ? 0 : Math.max(0, sources.getMaxFilesPerScan());

        for (Candidate candidate : candidates) {
            if (limit > 0 && processed >= limit) {
                truncated = true;
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                truncated = true;
                log.warn("目录导入被中断，剩余文件留到下一轮");
                break;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        candidate.path(), BasicFileAttributes.class);
                long size = attributes.size();
                long modified = attributes.lastModifiedTime().toMillis();
                SourceStateStore.FileState known = files.get(candidate.key());

                if (!force && known != null
                        && known.size() == size && known.modifiedMillis() == modified) {
                    skipped++;
                    continue;
                }
                if (sources.getMaxFileBytes() > 0 && size > sources.getMaxFileBytes()) {
                    skipped++;
                    log.warn("跳过超过大小上限的源文件: {} ({} > {} bytes)",
                            candidate.path(), size, sources.getMaxFileBytes());
                    continue;
                }

                byte[] bytes = Files.readAllBytes(candidate.path());
                String sha256 = sha256(bytes);
                if (!force && known != null && known.sha256().equals(sha256)) {
                    // 内容未变，只更新时间戳，避免下一轮反复读取
                    files.put(candidate.key(), new SourceStateStore.FileState(
                            known.documentId(), candidate.rootKey(), candidate.file(),
                            size, modified, sha256, Instant.now().toString()));
                    skipped++;
                    continue;
                }

                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1);
                }
                if (text.isBlank()) {
                    skipped++;
                    log.debug("跳过空白源文件: {}", candidate.path());
                    continue;
                }

                String documentId = known != null
                        ? known.documentId()
                        : documentIdFor(candidate.rootKey(), candidate.file());
                boolean existed = known != null || documentArchive.find(documentId).isPresent();
                Map<String, String> metadata = buildMetadata(sources, candidate, size, modified, sha256);
                ragService.upsert(documentId, text, metadata);
                files.put(candidate.key(), new SourceStateStore.FileState(
                        documentId, candidate.rootKey(), candidate.file(),
                        size, modified, sha256, Instant.now().toString()));
                if (existed) {
                    updated++;
                } else {
                    indexed++;
                }
                processed++;
            } catch (Exception e) {
                failed++;
                log.warn("导入源文件失败，将在下一轮重试: {} ({})", candidate.path(), e.toString());
            }
        }

        if (sources.isDeleteMissing()) {
            Iterator<Map.Entry<String, SourceStateStore.FileState>> iterator =
                    files.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, SourceStateStore.FileState> entry = iterator.next();
                SourceStateStore.FileState state = entry.getValue();
                if (!scannedRoots.contains(state.root()) || partialRoots.contains(state.root())) {
                    continue;
                }
                if (seenKeys.contains(entry.getKey())) {
                    continue;
                }
                try {
                    ragService.delete(state.documentId());
                    iterator.remove();
                    deleted++;
                    log.info("源文件已删除，索引已清理: {}", state.file());
                } catch (Exception e) {
                    failed++;
                    log.warn("删除已消失源文件的索引失败: {} ({})", state.file(), e.toString());
                }
            }
        }

        try {
            stateStore.save(new SourceStateStore.State(files));
        } catch (Exception e) {
            failed++;
            log.warn("保存目录导入状态失败: {}", e.toString());
        }

        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        String finishedAt = Instant.now().toString();
        Summary summary = new Summary(startedAt, finishedAt, durationMs, scannedRoots.size(),
                candidates.size(), indexed, updated, skipped, deleted, failed, truncated);
        log.info("目录导入完成: 发现={}, 新增={}, 更新={}, 跳过={}, 删除={}, 失败={}, 截断={}",
                candidates.size(), indexed, updated, skipped, deleted, failed, truncated);
        return summary;
    }

    private SourceStateStore.Snapshot loadState() {
        SourceStateStore.Snapshot snapshot = stateStore.load();
        if (snapshot.present()) {
            return snapshot;
        }
        Map<String, SourceStateStore.FileState> files = new LinkedHashMap<>();
        for (ArchivedDocument document : documentArchive.listAll()) {
            Map<String, String> metadata = document.metadata();
            if (metadata == null) {
                continue;
            }
            String key = metadata.get(META_SOURCE_KEY);
            String root = metadata.get(META_SOURCE_ROOT);
            String file = metadata.get(META_SOURCE_FILE);
            String sha256 = metadata.get(META_SOURCE_SHA256);
            if (key == null || root == null || file == null || sha256 == null) {
                continue;
            }
            long size = parseLong(metadata.get(META_SOURCE_SIZE));
            long modified = parseLong(metadata.get(META_SOURCE_MTIME));
            if (size < 0 || modified < 0) {
                continue;
            }
            files.put(key, new SourceStateStore.FileState(
                    document.documentId(), root, file, size, modified, sha256,
                    document.updatedAt()));
        }
        if (!files.isEmpty()) {
            log.info("从归档重建目录导入状态: {} 篇", files.size());
        }
        return new SourceStateStore.Snapshot(new SourceStateStore.State(files), false);
    }

    private void walk(Root root, RagProperties.Sources sources, List<Candidate> candidates,
            Set<Path> seenPaths, Set<String> seenKeys, Set<String> partialRoots) {
        Set<FileVisitOption> options = sources.isFollowSymlinks()
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);
        Set<PathMatcher> excludes = compileExcludes(sources.excludeList());
        Set<String> extensions = new HashSet<>(sources.extensionList());

        try {
            Files.walkFileTree(root.path(), options, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (!dir.equals(root.path())) {
                        if (sources.isSkipHidden() && isHidden(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (isExcluded(root.path().relativize(dir), true, excludes)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (sources.isSkipHidden() && isHidden(path)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = root.path().relativize(path);
                    if (!matchesExtension(relative, extensions)
                            || isExcluded(relative, false, excludes)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String file = toPosix(relative);
                    String key = key(root.key(), file);
                    seenKeys.add(key);
                    Path absolute = path.toAbsolutePath().normalize();
                    if (seenPaths.add(absolute)) {
                        candidates.add(new Candidate(root.key(), absolute, file, key));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) {
                    log.warn("访问源路径失败，本轮禁用该根目录的删除检测: {} ({})",
                            path, e.toString());
                    partialRoots.add(root.key());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            partialRoots.add(root.key());
            log.warn("扫描源目录失败，本轮禁用该根目录的删除检测: {} ({})",
                    root.path(), e.toString());
        }
    }

    private static List<Root> resolveRoots(RagProperties.Sources sources) {
        return sources.directoryList().stream()
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .map(path -> new Root(path, path.toString()))
                .toList();
    }

    private static Map<String, String> buildMetadata(RagProperties.Sources sources,
            Candidate candidate, long size, long modified, String sha256) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (sources.getMetadata() != null) {
            metadata.putAll(sources.getMetadata());
        }
        metadata.put(META_SOURCE_KEY, candidate.key());
        metadata.put(META_SOURCE_ROOT, candidate.rootKey());
        metadata.put(META_SOURCE_FILE, candidate.file());
        metadata.put(META_SOURCE_PATH, candidate.path().toString());
        metadata.put(META_SOURCE_SIZE, Long.toString(size));
        metadata.put(META_SOURCE_MTIME, Long.toString(modified));
        metadata.put(META_SOURCE_SHA256, sha256);
        metadata.put("source", rootLabel(candidate.rootKey()) + "/" + candidate.file());
        return metadata;
    }

    private static String rootLabel(String rootKey) {
        Path name = Path.of(rootKey).getFileName();
        return name == null ? rootKey : name.toString();
    }

    /**
     * 源文件 → 文档 id 的确定性映射；根目录或相对路径变化会生成新 id。
     */
    static String documentIdFor(String rootKey, String file) {
        return UUID.nameUUIDFromBytes(
                ("small-rag-source:" + rootKey + "::" + file).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String key(String rootKey, String file) {
        return rootKey + "::" + file;
    }

    private static Set<PathMatcher> compileExcludes(List<String> patterns) {
        Set<PathMatcher> matchers = new HashSet<>();
        for (String pattern : patterns) {
            try {
                matchers.add(java.nio.file.FileSystems.getDefault()
                        .getPathMatcher("glob:" + pattern));
            } catch (RuntimeException e) {
                log.warn("忽略非法的目录导入排除规则: {} ({})", pattern, e.toString());
            }
        }
        return matchers;
    }

    private static boolean isExcluded(Path relative, boolean directory, Set<PathMatcher> matchers) {
        String file = toPosix(relative);
        Path path = Path.of(file);
        // 以 / 开头的形式让 "**/name/**" 也能匹配顶层 name/
        Path rooted = Path.of("/" + file);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path) || matcher.matches(rooted)) {
                return true;
            }
            if (directory && (matcher.matches(path.resolve("__dir__"))
                    || matcher.matches(rooted.resolve("__dir__")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesExtension(Path relative, Set<String> extensions) {
        String name = relative.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return extensions.stream().anyMatch(name::endsWith);
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static long parseLong(String value) {
        try {
            return value == null ? -1L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("计算源文件 SHA-256 失败", e);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    private record Root(Path path, String key) {
    }

    private record Candidate(String rootKey, Path path, String file, String key) {
    }

    /**
     * 一次扫描的统计。
     *
     * @param startedAt  开始时间（ISO-8601）
     * @param finishedAt 结束时间（ISO-8601）
     * @param durationMs 耗时（毫秒）
     * @param roots      成功扫描的根目录数
     * @param discovered 发现的候选文件数
     * @param indexed    新增文档数
     * @param updated    更新文档数
     * @param skipped    跳过数（未变化/空白/超限）
     * @param deleted    删除文档数（delete-missing=true）
     * @param failed     失败数
     * @param truncated  是否因 max-files-per-scan 提前结束
     */
    public record Summary(
            String startedAt,
            String finishedAt,
            long durationMs,
            int roots,
            int discovered,
            int indexed,
            int updated,
            int skipped,
            int deleted,
            int failed,
            boolean truncated) {
    }

    /**
     * 目录导入状态。
     *
     * @param enabled             是否启用
     * @param running             是否正在运行
     * @param directories         配置的源目录
     * @param scanIntervalSeconds 定时扫描间隔（秒），{@code <= 0} 表示关闭
     * @param lastError           最近一次失败原因
     * @param lastRun             最近一次成功扫描统计
     */
    public record Status(
            boolean enabled,
            boolean running,
            List<String> directories,
            long scanIntervalSeconds,
            String lastError,
            Summary lastRun) {
    }
}
