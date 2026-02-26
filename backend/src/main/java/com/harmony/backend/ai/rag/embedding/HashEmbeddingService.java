package com.harmony.backend.ai.rag.embedding;

import com.harmony.backend.ai.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnMissingBean(org.springframework.ai.embedding.EmbeddingModel.class)
@RequiredArgsConstructor
public class HashEmbeddingService implements EmbeddingService {

    private final RagProperties properties;

    @Override
    public float[] embed(String text) {
        int size = Math.max(8, properties.getVectorSize());
        float[] vector = new float[size];
        if (!StringUtils.hasText(text)) {
            return vector;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int idx = Math.abs(bytes[i] * 31 + i) % size;
            vector[idx] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm <= 0.0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
