package com.harmony.backend.ai.rag.service.support;

import com.harmony.backend.ai.rag.service.OcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagMarkdownImageService {
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private final OcrService ocrService;
    private final RagOcrOptimizer ragOcrOptimizer;

    public String enrichMarkdownWithImageRefs(String markdownContent, String sourcePath) {
        Set<String> refs = extractImageRefs(markdownContent, sourcePath);
        if (refs.isEmpty()) {
            return markdownContent;
        }
        StringBuilder sb = new StringBuilder(markdownContent.trim());
        sb.append("\n\n---\nImage references");
        if (StringUtils.hasText(sourcePath)) {
            sb.append(" (source: ").append(sourcePath.trim()).append(")");
        }
        sb.append(":\n");
        for (String ref : refs) {
            sb.append("- ").append(ref).append('\n');
        }
        return sb.toString().trim();
    }

    public String embedImages(String markdownContent, Map<String, byte[]> images) {
        if (images == null || images.isEmpty()) {
            return markdownContent;
        }
        Map<String, String> ocrMap = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (!StringUtils.hasText(name) || bytes == null || bytes.length == 0) {
                continue;
            }
            if (!ragOcrOptimizer.shouldProcessImage(name, bytes)) {
                continue;
            }
            String ocrText = ragOcrOptimizer.cleanOcrText(ocrService.extractText(bytes, name, "image/*"));
            if (ragOcrOptimizer.isUsefulOcrText(ocrText)) {
                ocrMap.put(name.toLowerCase(), ocrText.trim());
            }
        }
        String withInlineOcr = insertOcrAfterImages(markdownContent, ocrMap);
        StringBuilder sb = new StringBuilder(withInlineOcr.trim());
        sb.append("\n\n---\nImage attachments\n");
        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            sb.append("- ").append(entry.getKey()).append("\n");
        }
        return sb.toString().trim();
    }

    private String insertOcrAfterImages(String markdownContent, Map<String, String> ocrMap) {
        if (ocrMap == null || ocrMap.isEmpty() || !StringUtils.hasText(markdownContent)) {
            return markdownContent;
        }
        StringBuilder output = new StringBuilder();
        Set<String> used = new HashSet<>();
        Matcher mdMatcher = MARKDOWN_IMAGE_PATTERN.matcher(markdownContent);
        int last = 0;
        while (mdMatcher.find()) {
            output.append(markdownContent, last, mdMatcher.end());
            String name = normalizeImageName(mdMatcher.group(1));
            String ocr = ocrMap.get(name);
            if (ocr != null && !ocr.isBlank()) {
                output.append("\n\n[OCR: ").append(name).append("]\n").append(ocr).append("\n");
                used.add(name);
            }
            last = mdMatcher.end();
        }
        output.append(markdownContent.substring(last));

        Pattern wiki = Pattern.compile("!\\[\\[([^\\]]+)\\]\\]");
        Matcher wikiMatcher = wiki.matcher(output.toString());
        StringBuffer sb = new StringBuffer();
        while (wikiMatcher.find()) {
            String name = normalizeImageName(wikiMatcher.group(1));
            String ocr = ocrMap.get(name);
            String replacement = wikiMatcher.group(0);
            if (ocr != null && !ocr.isBlank()) {
                replacement = replacement + "\n\n[OCR: " + name + "]\n" + ocr + "\n";
                used.add(name);
            }
            wikiMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        wikiMatcher.appendTail(sb);

        Pattern html = Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher htmlMatcher = html.matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (htmlMatcher.find()) {
            String name = normalizeImageName(htmlMatcher.group(1));
            String ocr = ocrMap.get(name);
            String replacement = htmlMatcher.group(0);
            if (ocr != null && !ocr.isBlank()) {
                replacement = replacement + "\n\n[OCR: " + name + "]\n" + ocr + "\n";
                used.add(name);
            }
            htmlMatcher.appendReplacement(sb2, Matcher.quoteReplacement(replacement));
        }
        htmlMatcher.appendTail(sb2);

        ArrayList<String> leftover = new ArrayList<>();
        for (String key : ocrMap.keySet()) {
            if (!used.contains(key)) {
                leftover.add(key);
            }
        }
        if (!leftover.isEmpty()) {
            sb2.append("\n\n---\nImage OCR (unreferenced)\n");
            for (String key : leftover) {
                sb2.append("[OCR: ").append(key).append("]\n").append(ocrMap.get(key)).append("\n");
            }
        }
        return sb2.toString();
    }

    private String normalizeImageName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String clean = raw.trim();
        int pipeIdx = clean.indexOf("|");
        if (pipeIdx > 0) {
            clean = clean.substring(0, pipeIdx);
        }
        int hashIdx = clean.indexOf("#");
        if (hashIdx > 0) {
            clean = clean.substring(0, hashIdx);
        }
        clean = clean.replace("\\", "/");
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            try {
                String path = URI.create(clean).getPath();
                if (path != null) {
                    clean = path;
                }
            } catch (Exception ignored) {
            }
        }
        String name = Paths.get(clean).getFileName().toString();
        return name == null ? "" : name.toLowerCase();
    }

    private Set<String> extractImageRefs(String markdownContent, String sourcePath) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(markdownContent);
        Path baseDir = null;
        if (StringUtils.hasText(sourcePath)) {
            try {
                Path source = Paths.get(sourcePath).normalize();
                baseDir = source.getParent();
            } catch (Exception ignored) {
                baseDir = null;
            }
        }
        while (matcher.find()) {
            String path = matcher.group(1);
            if (StringUtils.hasText(path)) {
                String normalized = path.trim();
                if (baseDir != null) {
                    try {
                        Path resolved = baseDir.resolve(normalized).normalize();
                        refs.add(resolved.toString().replace("\\", "/"));
                        continue;
                    } catch (Exception ignored) {
                    }
                }
                refs.add(normalized);
            }
        }
        return refs;
    }
}
