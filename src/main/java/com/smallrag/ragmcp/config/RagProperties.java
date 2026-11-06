package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * RAG 检索与切分配置，对应 {@code rag.*}。
 *
 * <p>这些值直接影响召回质量与 prompt 体积：相似度阈值过低会把噪声拼进上下文，
 * 上下文预算过高会稀释关键片段并浪费 token。
 *
 * @author zjk
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 未指定 topK 时使用的默认值。
     */
    private int defaultTopK = 4;

    /**
     * 单次检索允许的最大 topK，防止客户端传入过大值。
     */
    private int maxTopK = 20;

    /**
     * 相似度阈值（0~1，Qdrant Cosine）。低于该值的片段直接丢弃。
     */
    private double minScore = 0.35d;

    /**
     * MMR（最大边际相关）多样性权重 λ（0 < λ < 1 时启用）：候选按
     * {@code λ·score − (1−λ)·与已选结果的最大相似度} 迭代选取，在相关性与
     * 多样性之间折中。1 = 纯相关排序（关闭 MMR）。默认 0.7。
     */
    private double mmrLambda = 0.7d;

    /**
     * 固定长度/递归切分的目标块大小，同时作为语义切分的默认上限。
     */
    private int chunkSize = 800;

    /**
     * 相邻分块的重叠字符数，必须小于 chunkSize。
     */
    private int chunkOverlap = 100;

    /**
     * 拼入 prompt 的上下文总字符预算，超出后按相似度从高到低截断。
     */
    private int maxContextChars = 12000;

    /**
     * 单条上下文片段的硬截断长度。
     */
    private int maxChunkChars = 1200;

    /**
     * 知识库为空或没有命中时的固定答复（短路，不再调用模型）。
     */
    private String emptyContextAnswer =
            "未检索到相关内容。请先通过 rag_ingest 向知识库写入文本，或换一个查询词重试。";

    /**
     * 原始文档归档目录。自动重切依赖该目录中的原文；
     * 目录不可写时降级为“只入库、不自动重切”。
     */
    private String archiveDir = "./data/documents";

    /**
     * 切分策略及其参数。
     */
    private Chunking chunking = new Chunking();

    /**
     * Embedding HTTP 调用超时。
     */
    private Embedding embedding = new Embedding();

    /**
     * 异步重建任务的看门狗参数。
     */
    private Reindex reindex = new Reindex();

    /**
     * 切分配置。
     */
    @Data
    public static class Chunking {

        /**
         * 切分策略：FIXED、RECURSIVE、SEMANTIC、PARENT_CHILD。默认 Parent-Child。
         */
        private ChunkingStrategy strategy = ChunkingStrategy.PARENT_CHILD;

        /**
         * 启动时检测切分配置签名，发现变化后自动重切已归档文档。
         */
        private boolean autoRechunk = true;

        private Recursive recursive = new Recursive();

        private Semantic semantic = new Semantic();

        private ParentChild parentChild = new ParentChild();

        private Markdown markdown = new Markdown();
    }

    /**
     * 递归字符切分参数。
     */
    @Data
    public static class Recursive {

        /**
         * 分隔符优先级，从粗到细。按字面量匹配，不是正则表达式。
         */
        private List<String> separators = List.of(
                "\n\n", "\n", "。", "！", "？", "；", "…",
                ". ", "! ", "? ", "; ", "，", ", ", " ");
    }

    /**
     * 语义切分参数。
     */
    @Data
    public static class Semantic {

        /**
         * 文本块向量与当前块质心的合并阈值：余弦相似度低于该值时断开。
         */
        private double similarityThreshold = 0.75d;

        /**
         * 断点分位数（0~1）。大于 0 时忽略绝对阈值：取节内相邻文本块相似度的
         * 该分位数作为断点阈值（如 0.2 表示在相似度最低的 20% 边界处断开），
         * 对语言与模型的分布差异更鲁棒。默认 0，使用绝对阈值。
         */
        private double breakpointPercentile = 0d;

        /**
         * 目标最小块大小：未达到该长度时，即使相似度偏低也继续合并，避免碎块。
         */
        private int minChunkChars = 200;

        /**
         * 单块最大字符数，<= 0 时回落到 {@code rag.chunk-size}。
         */
        private int maxChunkChars = 0;

        /**
         * 单次 Embedding 请求的句子数。
         */
        private int batchSize = 64;
    }

    /**
     * Parent-Child 切分参数。
     */
    @Data
    public static class ParentChild {

        /**
         * 父块大小，提供较完整的上下文。
         */
        private int parentSize = 2000;

        /**
         * 父块之间的重叠字符数。
         *
         * <p>默认 0：子块不会跨父块边界切分，父块重叠只会让相邻父块内容
         * 重复、边界附近子块被重复嵌入，检索时产生重复命中。
         */
        private int parentOverlap = 0;

        /**
         * 子块大小，实际写入向量库并参与检索。
         */
        private int childSize = 400;

        /**
         * 子块之间的重叠字符数。
         */
        private int childOverlap = 50;
    }

    /**
     * Markdown / Obsidian 感知切分参数。
     */
    @Data
    public static class Markdown {

        /**
         * 标题路径分隔符，例如 " > " 会生成 "指南 > 安装 > Linux"。
         */
        private String headingSeparator = " > ";
    }

    /**
     * Embedding 服务调用超时。服务假死时避免重建线程被永久占用。
     */
    @Data
    public static class Embedding {

        /**
         * 建立连接超时（毫秒）。
         */
        private long connectTimeoutMs = 10_000;

        /**
         * 读取/写入/单次请求总超时（毫秒）。本地 llama-server 处理大批量时可能较慢。
         */
        private long readTimeoutMs = 120_000;
    }

    /**
     * 异步重建任务的看门狗参数：停滞检测只告警，硬超时才中断工作线程。
     */
    @Data
    public static class Reindex {

        /**
         * 超过该秒数没有进度事件视为停滞；{@code <= 0} 关闭停滞检测。
         */
        private long stallTimeoutSeconds = 1_800;

        /**
         * 停滞超过该秒数后中断工作线程；{@code <= 0} 关闭硬超时。
         */
        private long hardTimeoutSeconds = 3_600;

        /**
         * 看门狗检查间隔（秒），实际值至少 1 秒。
         */
        private long checkIntervalSeconds = 30;

        /**
         * 硬超时中断后等待工作线程退出的秒数。
         */
        private long joinTimeoutSeconds = 10;

        /**
         * 硬超时撤销写权限后，等待在途写入结束的宽限期（秒）；到期仍未退出则强制释放任务锁。
         *
         * <p>实际值不小于 Qdrant 常规 RPC deadline 的 2 倍：撤销后旧任务无法再发起新写入，
         * 只需覆盖撤销瞬间已经在途的请求。
         */
        private long recoveryGraceSeconds = 120;

        /**
         * 启动时是否自动续接重启前未完成的重建任务。
         *
         * <p>关闭后任务会停在 {@code interrupted} 并继续持有任务锁，
         * 可通过 {@code rag_task_resume} 手动续接或 {@code rag_task_cancel} 放弃。
         */
        private boolean resumeOnStartup = true;

        /**
         * 实际检查间隔，至少 1 秒。
         *
         * @return 检查间隔秒数
         */
        public long effectiveCheckIntervalSeconds() {
            return Math.max(1, checkIntervalSeconds);
        }

        /**
         * 实际宽限期：不小于 Qdrant deadline 的 2 倍。
         *
         * @param qdrantTimeoutMs Qdrant 常规 RPC deadline（毫秒）
         * @return 宽限期秒数
         */
        public long effectiveRecoveryGraceSeconds(long qdrantTimeoutMs) {
            long floor = Math.max(1, (Math.max(0, qdrantTimeoutMs) * 2) / 1000);
            return Math.max(recoveryGraceSeconds, floor);
        }
    }
}