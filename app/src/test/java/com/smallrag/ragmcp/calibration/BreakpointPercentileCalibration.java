package com.smallrag.ragmcp.calibration;

import com.smallrag.ragmcp.rag.chunking.MarkdownChunker;
import com.smallrag.ragmcp.rag.chunking.TextChunk;
import com.smallrag.ragmcp.rag.chunking.TextWeights;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 分位数断点真实语料校准（仅手动运行，不进入常规测试生命周期）：
 *
 * <pre>
 * JAVA_HOME='D:\java\jdk-27.0.1.0.3' mvn test -Dtest=BreakpointPercentileCalibration -Dcalibrate=true
 * </pre>
 *
 * <p>前置：本机已运行 OpenAI 兼容 Embedding 服务（如容器内 llama-server 或 Ollama），
 * 可用 {@code EMBEDDING_BASE_URL} / {@code EMBEDDING_MODEL} / {@code EMBEDDING_API_KEY} 覆盖默认值。
 * 语料取仓库内真实
 * Markdown 文档；对每个 breakpoint-percentile 输出块数与块大小分布，用于选定
 * 推荐默认值。
 *
 * @author zjk
 */
class BreakpointPercentileCalibration {

    /** OpenAI 兼容 Embedding 服务地址、模型名与密钥，均可用环境变量覆盖。 */
    private static final String BASE_URL =
            System.getenv().getOrDefault("EMBEDDING_BASE_URL", "http://127.0.0.1:11434/v1");
    private static final String MODEL = System.getenv().getOrDefault("EMBEDDING_MODEL", "qwen3-embedding-0.6b");
    private static final String API_KEY = System.getenv().getOrDefault("EMBEDDING_API_KEY", "local");

    private static final List<String> CORPUS_FILES = List.of(
            "README.md",
            "AGENTS.md",
            "DEV-MEMORY.md",
            "LICENSE");

    private static final double[] PERCENTILES = {0d, 0.1d, 0.15d, 0.2d, 0.25d, 0.3d, 0.4d, 0.5d};

    @Test
    @EnabledIfSystemProperty(named = "calibrate", matches = "true")
    void calibrateBreakpointPercentileOnRealCorpus() throws Exception {
        EmbeddingModel model = localOpenAiEmbeddingModel();
        List<String> documents = CORPUS_FILES.stream()
                .map(name -> {
                    try {
                        return name + "\n" + Files.readString(Path.of(name), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new IllegalStateException("读取语料失败: " + name, e);
                    }
                })
                .toList();
        long totalWeight = (long) documents.stream().mapToDouble(TextWeights::weight).sum();
        System.out.printf("语料: %d 篇, 总量 %d 全宽字符当量%n", documents.size(), totalWeight);
        System.out.printf("%-14s %8s %10s %10s %10s %10s %12s%n",
                "percentile", "chunks", "mean", "median", "p90", "max", "oversized");

        for (double percentile : PERCENTILES) {
            List<Integer> weights = new ArrayList<>();
            int oversized = 0;
            for (String document : documents) {
                List<TextChunk> chunks = MarkdownChunker.chunkSemantic(
                        document, 800, 200, " > ", Map.of(),
                        model, 0.75d, percentile, 64);
                for (TextChunk chunk : chunks) {
                    double weight = TextWeights.weight(chunk.text());
                    weights.add((int) Math.round(weight));
                    if (chunk.metadata().containsKey("oversized_block")) {
                        oversized++;
                    }
                }
            }
            weights.sort(Comparator.naturalOrder());
            double mean = weights.stream().mapToInt(Integer::intValue).average().orElse(0);
            int median = weights.isEmpty() ? 0 : weights.get(weights.size() / 2);
            int p90 = weights.isEmpty() ? 0 : weights.get((int) Math.min(weights.size() - 1, weights.size() * 0.9));
            int max = weights.isEmpty() ? 0 : weights.get(weights.size() - 1);
            System.out.printf("%-14s %8d %10.0f %10d %10d %10d %12d%n",
                    percentile == 0d ? "0(绝对0.75)" : percentile,
                    weights.size(), mean, median, p90, max, oversized);
        }
    }

    /**
     * 最小 EmbeddingModel 适配器：直接调用 OpenAI 兼容的 /v1/embeddings
     * （llama.cpp llama-server / Ollama / 云端服务均可），避免为校准引入
     * OpenAI SDK 客户端模块依赖。
     */
    private static EmbeddingModel localOpenAiEmbeddingModel() {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return embedBatch(request.getInstructions());
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return embedBatch(texts).getResults().stream()
                        .map(Embedding::getOutput)
                        .toList();
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                return embedBatch(List.of(document.getText())).getResults().get(0).getOutput();
            }

            private EmbeddingResponse embedBatch(List<String> texts) {
                try {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("model", MODEL);
                    payload.put("input", texts);
                    String body = mapper.writeValueAsString(payload);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/embeddings"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + API_KEY)
                            .timeout(Duration.ofMinutes(5))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> response =
                            http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Embedding HTTP " + response.statusCode()
                                + ": " + response.body());
                    }
                    JsonNode root = mapper.readTree(response.body());
                    Map<Integer, float[]> byIndex = new HashMap<>();
                    for (JsonNode item : root.get("data")) {
                        JsonNode vector = item.get("embedding");
                        float[] values = new float[vector.size()];
                        for (int i = 0; i < vector.size(); i++) {
                            values[i] = vector.get(i).floatValue();
                        }
                        byIndex.put(item.get("index").asInt(), values);
                    }
                    List<Embedding> embeddings = byIndex.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> new Embedding(entry.getValue(), entry.getKey()))
                            .collect(Collectors.toList());
                    return new EmbeddingResponse(embeddings);
                } catch (java.io.IOException | InterruptedException e) {
                    throw new IllegalStateException("调用 Embedding 服务失败", e);
                }
            }
        };
    }
}
