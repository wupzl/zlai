package com.harmony.backend.ai.rag.service.support;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.model.PreparedRagChunk;
import com.harmony.backend.common.util.TokenCounter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Service
public class RagChunkingService {

    private final RagProperties properties;

    public RagChunkingService(RagProperties properties) {
        this.properties = properties;
    }

    public List<PreparedRagChunk> chunk(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        int targetTokens = Math.max(120, properties.getChunkTokenSize());
        int overlapTokens = Math.max(0, Math.min(properties.getChunkTokenOverlap(), targetTokens / 3));
        List<Block> blocks = parseBlocks(content);
        List<PreparedRagChunk> chunks = new ArrayList<>();
        List<Block> currentBlocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        int ordinal = 0;
        for (Block block : blocks) {
            if (block == null || !StringUtils.hasText(block.content())) {
                continue;
            }
            String blockText = decorateWithHeading(block);
            int blockTokens = TokenCounter.estimateTokens(blockText);
            if (blockTokens > targetTokens) {
                if (currentTokens > 0) {
                    chunks.add(toChunk(current.toString(), currentBlocks, ordinal++));
                    current.setLength(0);
                    currentBlocks.clear();
                    currentTokens = 0;
                }
                List<PreparedRagChunk> oversized = splitOversizedBlock(block, targetTokens, overlapTokens, ordinal);
                chunks.addAll(oversized);
                ordinal += oversized.size();
                continue;
            }
            if (currentTokens > 0 && shouldFlushForHeadingBoundary(currentBlocks, block)) {
                chunks.add(toChunk(current.toString(), currentBlocks, ordinal++));
                current.setLength(0);
                currentBlocks.clear();
                currentTokens = 0;
            }
            if (currentTokens > 0 && currentTokens + blockTokens > targetTokens) {
                chunks.add(toChunk(current.toString(), currentBlocks, ordinal++));
                current.setLength(0);
                currentBlocks.clear();
                currentTokens = 0;
            }
            if (currentTokens > 0) {
                current.append("\n\n");
            }
            current.append(blockText);
            currentBlocks.add(block);
            currentTokens = TokenCounter.estimateTokens(current.toString());
        }
        if (currentTokens > 0) {
            chunks.add(toChunk(current.toString(), currentBlocks, ordinal));
        }
        return chunks.stream().filter(chunk -> StringUtils.hasText(chunk.getContent())).toList();
    }


    private boolean shouldFlushForHeadingBoundary(List<Block> currentBlocks, Block nextBlock) {
        if (currentBlocks == null || currentBlocks.isEmpty() || nextBlock == null) {
            return false;
        }
        List<String> currentHeadings = currentBlocks.get(currentBlocks.size() - 1).headings();
        List<String> nextHeadings = nextBlock.headings();
        if (currentHeadings == null || nextHeadings == null) {
            return false;
        }
        return !currentHeadings.equals(nextHeadings);
    }
    private PreparedRagChunk toChunk(String content, List<Block> blocks, int ordinal) {
        List<String> headings = List.of();
        String blockType = "paragraph";
        if (blocks != null && !blocks.isEmpty()) {
            headings = blocks.get(blocks.size() - 1).headings();
            boolean sameType = blocks.stream().map(Block::type).distinct().count() == 1;
            blockType = sameType ? blocks.get(0).type() : "mixed";
        }
        String normalized = content == null ? "" : content.trim();
        return new PreparedRagChunk(normalized, blockType, headings, ordinal,
                TokenCounter.estimateTokens(normalized));
    }

    private List<Block> parseBlocks(String content) {
        List<Block> blocks = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();
        List<String> lines = List.of(content.split("\n", -1));
        StringBuilder current = new StringBuilder();
        String currentType = "paragraph";
        boolean inCode = false;
        for (String rawLine : lines) {
            String line = rstrip(rawLine);
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inCode && current.length() > 0) {
                    flushBlock(blocks, current, currentType, headingStack);
                }
                inCode = !inCode;
                currentType = "code";
                current.append(line).append("\n");
                if (!inCode) {
                    flushBlock(blocks, current, currentType, headingStack);
                    currentType = "paragraph";
                }
                continue;
            }
            if (inCode) {
                current.append(line).append("\n");
                continue;
            }
            if (trimmed.matches("^#{1,6}\\s+.*")) {
                if (current.length() > 0) {
                    flushBlock(blocks, current, currentType, headingStack);
                }
                int level = headingLevel(trimmed);
                while (headingStack.size() >= level) {
                    headingStack.removeLast();
                }
                String heading = trimmed.replaceFirst("^#{1,6}\\s+", "").trim();
                headingStack.addLast(heading);
                blocks.add(new Block("heading", heading, List.copyOf(headingStack)));
                currentType = "paragraph";
                continue;
            }
            if (trimmed.isBlank()) {
                flushBlock(blocks, current, currentType, headingStack);
                currentType = "paragraph";
                continue;
            }
            String blockType = detectBlockType(trimmed);
            if (current.length() > 0 && !currentType.equals(blockType)) {
                flushBlock(blocks, current, currentType, headingStack);
            }
            currentType = blockType;
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        flushBlock(blocks, current, currentType, headingStack);
        return blocks;
    }

    private void flushBlock(List<Block> blocks, StringBuilder current, String type, Deque<String> headingStack) {
        String text = current.toString().trim();
        if (StringUtils.hasText(text)) {
            blocks.add(new Block(type, text, List.copyOf(headingStack)));
        }
        current.setLength(0);
    }

    private String decorateWithHeading(Block block) {
        if ("heading".equals(block.type()) || block.headings().isEmpty()) {
            return block.content();
        }
        String prefix = "[Section] " + String.join(" > ", block.headings());
        if (block.content().startsWith(prefix)) {
            return block.content();
        }
        return prefix + "\n" + block.content();
    }

    private List<PreparedRagChunk> splitOversizedBlock(Block block, int targetTokens, int overlapTokens, int startOrdinal) {
        List<String> sentences = splitSentences(decorateWithHeading(block));
        List<PreparedRagChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int ordinal = startOrdinal;
        for (String sentence : sentences) {
            String normalized = sentence == null ? "" : sentence.trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (TokenCounter.estimateTokens(normalized) > targetTokens) {
                if (current.length() > 0) {
                    chunks.add(toChunk(current.toString(), List.of(block), ordinal++));
                    current.setLength(0);
                }
                for (String hardChunk : splitHard(normalized, targetTokens, overlapTokens)) {
                    chunks.add(toChunk(hardChunk, List.of(block), ordinal++));
                }
                continue;
            }
            String candidate = current.length() == 0 ? normalized : current + "\n" + normalized;
            if (TokenCounter.estimateTokens(candidate) > targetTokens) {
                chunks.add(toChunk(current.toString(), List.of(block), ordinal++));
                current.setLength(0);
                current.append(normalized);
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(normalized);
            }
        }
        if (current.length() > 0) {
            chunks.add(toChunk(current.toString(), List.of(block), ordinal));
        }
        return chunks;
    }

    private List<String> splitHard(String text, int targetTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = findChunkEnd(text, start, targetTokens);
            if (end <= start) {
                break;
            }
            String chunk = text.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }
            start = findOverlapStart(text, start, end, overlapTokens);
            if (start <= 0 || start >= end) {
                start = end;
            }
        }
        return chunks;
    }

    private int findChunkEnd(String text, int start, int targetTokens) {
        int low = start + 1;
        int high = Math.min(text.length(), start + Math.max(160, targetTokens * 6));
        while (high < text.length() && TokenCounter.estimateTokens(text.substring(start, high)) < targetTokens) {
            int nextHigh = Math.min(text.length(), high + Math.max(120, targetTokens * 3));
            if (nextHigh == high) {
                break;
            }
            high = nextHigh;
        }
        int best = low;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int tokens = TokenCounter.estimateTokens(text.substring(start, mid));
            if (tokens <= targetTokens) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return adjustBoundary(text, start, best);
    }

    private int adjustBoundary(String text, int start, int end) {
        int min = Math.min(text.length(), start + 40);
        int candidate = end;
        for (int i = end - 1; i >= min; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':'
                    || c == '\u3002' || c == '\uFF01' || c == '\uFF1F' || c == '\uFF1B' || c == '\uFF1A') {
                candidate = i + 1;
                break;
            }
        }
        return Math.max(min, candidate);
    }

    private int findOverlapStart(String text, int chunkStart, int chunkEnd, int overlapTokens) {
        if (overlapTokens <= 0) {
            return chunkEnd;
        }
        int low = chunkStart;
        int high = chunkEnd;
        int best = chunkEnd;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int tokens = TokenCounter.estimateTokens(text.substring(mid, chunkEnd));
            if (tokens >= overlapTokens) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(chunkStart, Math.min(best, chunkEnd - 1));
    }

    private List<String> splitSentences(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == ';'
                    || c == '\u3002' || c == '\uFF01' || c == '\uFF1F' || c == '\uFF1B') {
                String sentence = current.toString().trim();
                if (StringUtils.hasText(sentence)) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString().trim());
        }
        return sentences;
    }

    public List<PreparedRagChunk> chunkMarkdown(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        int targetTokens = Math.max(120, properties.getChunkTokenSize());
        int overlapTokens = Math.max(0, Math.min(properties.getChunkTokenOverlap(), targetTokens / 3));
        List<Section> sections = parseMarkdownSections(content);
        List<PreparedRagChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (Section section : sections) {
            if (section == null || !StringUtils.hasText(section.content())) {
                continue;
            }
            List<PreparedRagChunk> sectionChunks = splitSection(section, targetTokens, overlapTokens, ordinal);
            chunks.addAll(sectionChunks);
            ordinal += sectionChunks.size();
        }
        return chunks.stream().filter(chunk -> StringUtils.hasText(chunk.getContent())).toList();
    }

    private List<Section> parseMarkdownSections(String content) {
        List<Section> sections = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        List<String> currentHeadings = List.of();
        StringBuilder current = new StringBuilder();
        boolean inCode = false;
        for (String rawLine : List.of(content.split("\n", -1))) {
            String line = rstrip(rawLine);
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                current.append(line).append('\n');
                continue;
            }
            if (!inCode && trimmed.matches("^#{1,6}\s+.*")) {
                flushSection(sections, current, currentHeadings);
                int level = headingLevel(trimmed);
                while (!headingLevels.isEmpty() && headingLevels.get(headingLevels.size() - 1) >= level) {
                    headingLevels.remove(headingLevels.size() - 1);
                    headingStack.remove(headingStack.size() - 1);
                }
                String heading = trimmed.replaceFirst("^#{1,6}\s+", "").trim();
                headingLevels.add(level);
                headingStack.add(heading);
                currentHeadings = List.copyOf(headingStack);
                current.append(line).append('\n');
                continue;
            }
            current.append(line).append('\n');
        }
        flushSection(sections, current, currentHeadings);
        return sections;
    }

    private void flushSection(List<Section> sections, StringBuilder current, List<String> headings) {
        String text = current.toString().trim();
        if (!StringUtils.hasText(text)) {
            current.setLength(0);
            return;
        }
        sections.add(new Section(text, headings == null ? List.of() : List.copyOf(headings)));
        current.setLength(0);
    }

    private List<PreparedRagChunk> splitSection(Section section, int targetTokens, int overlapTokens, int startOrdinal) {
        String normalized = section.content() == null ? "" : section.content().trim();
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        if (TokenCounter.estimateTokens(normalized) <= targetTokens) {
            return List.of(toSectionChunk(normalized, section, startOrdinal));
        }
        List<PreparedRagChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int ordinal = startOrdinal;
        for (String sentence : splitSentences(normalized)) {
            String candidateSentence = sentence == null ? "" : sentence.trim();
            if (!StringUtils.hasText(candidateSentence)) {
                continue;
            }
            if (TokenCounter.estimateTokens(candidateSentence) > targetTokens) {
                if (current.length() > 0) {
                    chunks.add(toSectionChunk(current.toString(), section, ordinal++));
                    current.setLength(0);
                }
                for (String hardChunk : splitHard(candidateSentence, targetTokens, overlapTokens)) {
                    chunks.add(toSectionChunk(hardChunk, section, ordinal++));
                }
                continue;
            }
            String candidate = current.length() == 0 ? candidateSentence : current + "\n" + candidateSentence;
            if (TokenCounter.estimateTokens(candidate) > targetTokens) {
                chunks.add(toSectionChunk(current.toString(), section, ordinal++));
                current.setLength(0);
                current.append(candidateSentence);
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(candidateSentence);
            }
        }
        if (current.length() > 0) {
            chunks.add(toSectionChunk(current.toString(), section, ordinal));
        }
        return chunks;
    }

    private PreparedRagChunk toSectionChunk(String content, Section section, int ordinal) {
        String normalized = content == null ? "" : content.trim();
        if (section != null && section.headings() != null && !section.headings().isEmpty()) {
            String prefix = "[Section] " + String.join(" > ", section.headings());
            if (!normalized.startsWith(prefix)) {
                normalized = prefix + "\n" + normalized;
            }
        }
        List<String> headings = section == null || section.headings() == null ? List.of() : List.copyOf(section.headings());
        return new PreparedRagChunk(normalized, "section", headings, ordinal,
                TokenCounter.estimateTokens(normalized));
    }


    private int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        return Math.max(1, level);
    }

    private String detectBlockType(String trimmed) {
        if (trimmed.startsWith("|") || trimmed.matches("^[-:| ]+$")) {
            return "table";
        }
        if (trimmed.matches("^\\d+[\\.)].*") || trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return "list";
        }
        if (trimmed.startsWith(">")) {
            return "quote";
        }
        return "paragraph";
    }

    private String rstrip(String rawLine) {
        return rawLine == null ? "" : rawLine.replaceFirst("\\s+$", "");
    }

    private record Section(String content, List<String> headings) {
    }

    private record Block(String type, String content, List<String> headings) {
    }
}


