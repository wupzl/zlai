package com.harmony.backend.ai.rag.service.support;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

@Service
public class RagOcrOptimizer {

    private final RagContentCleaner ragContentCleaner;

    public RagOcrOptimizer(RagContentCleaner ragContentCleaner) {
        this.ragContentCleaner = ragContentCleaner;
    }

    public boolean shouldProcessImage(String name, byte[] bytes) {
        if (bytes == null || bytes.length < 1024) {
            return false;
        }
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.contains("icon") || lower.contains("logo") || lower.contains("avatar")
                || lower.contains("banner") || lower.contains("cover")) {
            return false;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return true;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width < 80 || height < 40) {
                return false;
            }
            double ratio = width >= height ? (double) width / Math.max(1, height) : (double) height / Math.max(1, width);
            return ratio <= 10.0;
        } catch (Exception ignored) {
            return true;
        }
    }

    public String cleanOcrText(String rawText) {
        return ragContentCleaner.cleanOcrText(rawText);
    }

    public boolean isUsefulOcrText(String rawText) {
        return ragContentCleaner.looksUsefulOcrText(cleanOcrText(rawText));
    }

    public boolean shouldUsePdfOcr(String baseText) {
        if (!StringUtils.hasText(baseText)) {
            return true;
        }
        String trimmed = baseText.trim();
        if (trimmed.length() < 180) {
            return true;
        }
        return qualityScore(trimmed) < 80;
    }

    public double qualityScore(String text) {
        if (!StringUtils.hasText(text)) {
            return 0.0;
        }
        int len = text.length();
        int replacement = 0;
        int cjk = 0;
        int ascii = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD') {
                replacement++;
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else if (c >= 0x20 && c <= 0x7E) {
                ascii++;
            }
        }
        double replacementPenalty = replacement * 5.0;
        double cjkBonus = cjk * 2.0;
        double asciiBonus = ascii * 0.2;
        return cjkBonus + asciiBonus - replacementPenalty;
    }
}
