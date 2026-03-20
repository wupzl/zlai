package com.harmony.backend.ai.rag.service.support;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagContentCleaner {

    public String cleanDocument(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return "";
        }
        String normalized = normalizeCommon(rawContent);
        List<String> lines = List.of(normalized.split("\n", -1));
        Map<String, Integer> frequencies = buildLineFrequency(lines);
        List<String> cleanedLines = new ArrayList<>();
        boolean inCodeBlock = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                cleanedLines.add(trimmed);
                continue;
            }
            if (!inCodeBlock && shouldDropNoiseLine(trimmed, frequencies)) {
                continue;
            }
            cleanedLines.add(rstrip(line));
        }
        return mergeWrappedLines(cleanedLines);
    }

    public String cleanOcrText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String normalized = normalizeCommon(rawText)
                .replaceAll("(?<=\\p{L}|\\p{IsHan})-\\n(?=\\p{L}|\\p{IsHan})", "")
                .replaceAll("[ \t]+", " ");
        List<String> output = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed)) {
                if (!output.isEmpty() && !output.get(output.size() - 1).isBlank()) {
                    output.add("");
                }
                continue;
            }
            if (isLowSignalOcrLine(trimmed)) {
                continue;
            }
            output.add(trimmed);
        }
        return mergeWrappedLines(output);
    }

    public boolean looksUsefulOcrText(String cleanedText) {
        if (!StringUtils.hasText(cleanedText)) {
            return false;
        }
        int lettersOrCjk = 0;
        int digits = 0;
        int weird = 0;
        for (int i = 0; i < cleanedText.length(); i++) {
            char c = cleanedText.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (Character.isDigit(c)) {
                    digits++;
                } else {
                    lettersOrCjk++;
                }
            } else if (!Character.isWhitespace(c) && ",.;:!?-_/()[]{}<>|@#%&+'\"".indexOf(c) < 0) {
                weird++;
            }
        }
        if (lettersOrCjk + digits < 12) {
            return false;
        }
        return weird < Math.max(8, cleanedText.length() / 5);
    }

    private String normalizeCommon(String raw) {
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("(?m)[ ]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private Map<String, Integer> buildLineFrequency(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            counts.merge(trimmed, 1, Integer::sum);
        }
        return counts;
    }

    private boolean shouldDropNoiseLine(String trimmed, Map<String, Integer> frequencies) {
        if (!StringUtils.hasText(trimmed)) {
            return false;
        }
        if (trimmed.matches("(?i)^page\\s+\\d+(\\s*/\\s*\\d+|\\s+of\\s+\\d+)?$")) {
            return true;
        }
        if (trimmed.matches("^第\\s*\\d+\\s*页(\\s*/\\s*共?\\s*\\d+\\s*页)?$")) {
            return true;
        }
        Integer count = frequencies.get(trimmed);
        if (count == null || count < 3) {
            return false;
        }
        if (trimmed.length() > 80) {
            return false;
        }
        if (trimmed.startsWith("#") || trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("|")) {
            return false;
        }
        return !trimmed.matches(".*[。！？!?;；:]$");
    }

    private boolean isLowSignalOcrLine(String trimmed) {
        if (!StringUtils.hasText(trimmed)) {
            return true;
        }
        if (trimmed.length() <= 2 && !trimmed.matches(".*\\d.*")) {
            return true;
        }
        if (trimmed.matches("^[\\p{Punct}\\s]+$")) {
            return true;
        }
        int alphaNum = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetterOrDigit(trimmed.charAt(i))) {
                alphaNum++;
            }
        }
        return alphaNum == 0;
    }

    private String mergeWrappedLines(List<String> lines) {
        StringBuilder output = new StringBuilder();
        boolean inCodeBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (line.startsWith("```")) {
                if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                    output.append('\n');
                }
                output.append(line).append('\n');
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (line.isBlank()) {
                if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                    output.append('\n');
                }
                if (output.length() == 0 || output.charAt(output.length() - 1) != '\n') {
                    output.append('\n');
                }
                continue;
            }
            if (output.length() == 0) {
                output.append(line);
                continue;
            }
            char lastChar = output.charAt(output.length() - 1);
            boolean shouldJoin = !inCodeBlock
                    && lastChar != '\n'
                    && !startsStructuralBlock(line)
                    && !endsSentence(output)
                    && !isLikelyListContinuation(line);
            if (shouldJoin) {
                output.append(' ').append(line);
            } else {
                if (lastChar != '\n') {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        return output.toString()
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private boolean startsStructuralBlock(String line) {
        return line.startsWith("#")
                || line.startsWith("|")
                || line.startsWith("```")
                || line.matches("^\\d+[\\.)].*")
                || line.startsWith("- ")
                || line.startsWith("* ")
                || line.startsWith("> ");
    }

    private boolean isLikelyListContinuation(String line) {
        return line.matches("^[a-zA-Z]\\)|^[ivxIVX]+\\.|^[（(][一二三四五六七八九十0-9]+[)）].*");
    }

    private boolean endsSentence(CharSequence text) {
        if (text.length() == 0) {
            return false;
        }
        char c = text.charAt(text.length() - 1);
        return c == '.'
                || c == '!'
                || c == '?'
                || c == ';'
                || c == ':'
                || c == '。'
                || c == '！'
                || c == '？'
                || c == '；'
                || c == '：';
    }

    private String rstrip(String line) {
        return line == null ? "" : line.replaceFirst("\\s+$", "");
    }
}
