package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 用很小的块（最多 20 字、重叠 5 字）来测，方便手算结果 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(20, 5);

    @Test
    void shortTextIsOneChunk() {
        assertThat(chunker.split("你好，世界")).containsExactly("你好，世界");
    }

    @Test
    void smallParagraphsAreMergedIntoOneChunk() {
        assertThat(chunker.split("第一段\n\n第二段")).containsExactly("第一段\n\n第二段");
    }

    @Test
    void newChunkStartsWithTheTailOfThePreviousOne() {
        String first = "一二三四五六七八九十一二三四五";   // 15 字
        String second = "甲乙丙丁戊己庚辛";               // 8 字，合并后超过 20 字

        List<String> chunks = chunker.split(first + "\n\n" + second);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(first);
        // 第二块开头是第一块的最后 5 个字（重叠），这样跨块的那句话两边都能看到
        assertThat(chunks.get(1)).startsWith(first.substring(10)).endsWith(second);
    }

    @Test
    void longParagraphIsSplitAtSentenceEnds() {
        String sentence = "一二三四五六七八。";               // 9 字
        String paragraph = sentence.repeat(4);              // 36 字，一个段落

        List<String> chunks = chunker.split(paragraph);

        assertThat(chunks).hasSize(2);
        // 在句号处断开，而不是在句子中间
        assertThat(chunks.get(0)).isEqualTo(sentence + sentence);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(20 + 5 + 2));
    }

    @Test
    void sentenceWithoutPunctuationIsCutHard() {
        List<String> chunks = chunker.split("a".repeat(45));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(20);
    }

    @Test
    void windowsLineBreaksAreNormalized() {
        assertThat(chunker.split("第一段\r\n\r\n第二段")).containsExactly("第一段\n\n第二段");
    }

    @Test
    void blankTextHasNoChunks() {
        assertThat(chunker.split("  \n\n  ")).isEmpty();
        assertThat(chunker.split(null)).isEmpty();
    }

    @Test
    void overlapMustBeAtMostHalfOfTheChunk() {
        assertThatThrownBy(() -> new TextChunker(10, 6)).isInstanceOf(IllegalArgumentException.class);
    }
}
