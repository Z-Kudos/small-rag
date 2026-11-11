package com.smallrag.ragmcp.rag.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 测试用 EmbeddingModel：按 token 哈希生成确定性向量，可离线跑 "入库 → 检索" 流程。
 *
 * @author zjk
 */
public final class TestEmbeddingModels {

    private TestEmbeddingModels() {
    }

    public static EmbeddingModel hashing(int dimensions) {
        return new HashingEmbeddingModel(dimensions);
    }

    /**
     * 返回固定向量（仅用于验证维度校验等不关心语义的场景）。
     */
    public static EmbeddingModel fixed(float[] vector) {
        return new fixedVectorEmbeddingModel(vector);
    }

    private static final class fixedVectorEmbeddingModel implements EmbeddingModel {
        private final float[] vector;

        private fixedVectorEmbeddingModel(float[] vector) {
            this.vector = vector;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(cloneVector(), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return cloneVector();
        }

        @Override
        public int dimensions() {
            return vector.length;
        }

        private float[] cloneVector() {
            return vector.clone();
        }
    }

    private static final class HashingEmbeddingModel implements EmbeddingModel {

        private final int dimensions;

        private HashingEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(embedText(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embedText(document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] embedText(String text) {
            float[] vector = new float[dimensions];
            if (text == null || text.isBlank()) {
                return vector;
            }
            for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() < 2) {
                    continue;
                }
                int hash = token.hashCode();
                int index = Math.floorMod(hash, dimensions);
                float sign = ((hash >>> 1) & 1) == 0 ? 1f : -1f;
                vector[index] += sign;
            }
            double norm = 0d;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm > 0) {
                float scale = (float) (1d / Math.sqrt(norm));
                for (int i = 0; i < vector.length; i++) {
                    vector[i] *= scale;
                }
            }
            return vector;
        }
    }
}
