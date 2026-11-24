package com.nick.jarvis.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把一份资料切成适合向量化的小块。
 *
 * 规则：
 * 1. 先按空行切成段落，尽量不在段落中间断开；
 * 2. 超长的段落，按句子结尾（。！？；等）再切；连句号都没有的超长句子，只能硬切；
 * 3. 相邻的短段落合并，直到接近 maxChars；
 * 4. 每一块开头带上前一块末尾的 overlapChars 个字（重叠），
 *    避免一句关键的话正好被切在两块之间，两边都看不全。
 *
 * 所以每块最多 maxChars + overlapChars 个字左右（另加段落之间的换行）。
 * 不用 Spring AI 自带的 TokenTextSplitter：它主要按英文句号切分，对中文不太友好。
 */
public class TextChunker {

    /** 句子结尾：中文标点、英文的 ! ? ;、换行，以及“英文句号 + 空格”（避免把 3.14 切开） */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;\\n])|(?<=\\. )");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    private final int maxChars;
    private final int overlapChars;

    public TextChunker(int maxChars, int overlapChars) {
        if (maxChars <= 0 || overlapChars < 0 || overlapChars * 2 > maxChars) {
            throw new IllegalArgumentException("overlapChars must be at most half of maxChars");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public record Chunk(String text, int start, int end) {
    }

    public List<String> split(String text) {
        return splitLocated(text).stream().map(Chunk::text).toList();
    }

    /** 起止位置是 MySQL 原文的 Java 字符下标，end 不包含在片段内。 */
    public List<Chunk> splitLocated(String text) {
        if (text == null) {
            return List.of();
        }
        // 统一换行符：Windows 是 \r\n，老的 Mac 是 \r
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> pieces = new ArrayList<>();
        for (String paragraph : BLANK_LINE.split(normalized)) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= maxChars) {
                pieces.add(p);
            } else {
                pieces.addAll(splitLongParagraph(p));
            }
        }
        // 换行归一化后，CRLF 少了一个字符。保留映射，预览仍能截取上传时的原文。
        int[] offsets = new int[normalized.length() + 1];
        int raw = 0;
        for (int i = 0; i < normalized.length(); i++) {
            offsets[i] = raw;
            if (text.startsWith("\r\n", raw)) raw++;
            raw++;
        }
        offsets[normalized.length()] = raw;
        return merge(pieces, normalized, offsets);
    }

    /** 把超长段落按句子切开，再把句子装进不超过 maxChars 的小段 */
    private List<String> splitLongParagraph(String paragraph) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_END.split(paragraph)) {
            // 单个句子就超长：只能硬切
            while (sentence.length() > maxChars) {
                flush(current, result);
                result.add(sentence.substring(0, maxChars));
                sentence = sentence.substring(maxChars);
            }
            if (current.length() + sentence.length() > maxChars) {
                flush(current, result);
            }
            current.append(sentence);
        }
        flush(current, result);
        return result;
    }

    /** 把攒着的内容作为一小段收下；只有空白的直接丢掉 */
    private static void flush(StringBuilder current, List<String> result) {
        String piece = current.toString().strip();
        if (!piece.isEmpty()) {
            result.add(piece);
        }
        current.setLength(0);
    }

    /** 合并相邻的小段，满了就开始新的一块，新块开头带上前一块的末尾 */
    private List<Chunk> merge(List<String> pieces, String normalized, int[] offsets) {
        List<Chunk> chunks = new ArrayList<>();
        // 与 current 中的字符同步，保留重叠部分在原文的位置。
        List<Integer> positions = new ArrayList<>();
        int cursor = 0;
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            int start = normalized.indexOf(piece, cursor);
            if (start < 0) throw new IllegalStateException("无法定位资料片段");
            if (!current.isEmpty() && current.length() + PARAGRAPH_SEPARATOR.length() + piece.length() > maxChars) {
                String finished = current.toString();
                chunks.add(new Chunk(finished, positions.get(0), offsets[cursor]));
                current = new StringBuilder(tail(finished));
                positions = new ArrayList<>(positions.subList(positions.size() - current.length(), positions.size()));
            }
            if (!current.isEmpty()) {
                current.append(PARAGRAPH_SEPARATOR);
                positions.add(offsets[cursor]);
                positions.add(offsets[cursor]);
            }
            current.append(piece);
            cursor = start + piece.length();
            for (int i = start; i < cursor; i++) positions.add(offsets[i]);
        }
        if (!current.isEmpty()) {
            chunks.add(new Chunk(current.toString(), positions.get(0), offsets[cursor]));
        }
        return chunks;
    }

    private String tail(String chunk) {
        if (overlapChars == 0) {
            return "";
        }
        return chunk.length() <= overlapChars ? chunk : chunk.substring(chunk.length() - overlapChars);
    }
}
