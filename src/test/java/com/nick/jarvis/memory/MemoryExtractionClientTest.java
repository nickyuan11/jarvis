package com.nick.jarvis.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nick.jarvis.memory.MemoryExtractionClient.Operation;

class MemoryExtractionClientTest {

    @Test
    void parsesAllThreeKindsOfOperations() {
        List<Operation> ops = MemoryExtractionClient.parse("""
                ADD|FACT|在找 Java 后端的工作
                UPDATE|#12|FACT|已经入职新公司
                DELETE|7
                """);

        assertThat(ops).containsExactly(
                new Operation("ADD", null, "FACT", "在找 Java 后端的工作"),
                new Operation("UPDATE", 12L, "FACT", "已经入职新公司"),
                new Operation("DELETE", 7L, null, null));
    }

    @Test
    void noneMeansNoOperations() {
        assertThat(MemoryExtractionClient.parse("NONE")).isEmpty();
        assertThat(MemoryExtractionClient.parse(null)).isEmpty();
    }

    @Test
    void ignoresLinesItDoesNotUnderstand() {
        List<Operation> ops = MemoryExtractionClient.parse("""
                好的，以下是需要的改动：
                - ADD|PREFERENCE|希望回答简短
                DELETE|abc
                UPDATE|3|FACT
                """);

        // 废话、编号不是数字、缺少内容的行都被忽略，只留下能看懂的那一条
        assertThat(ops).containsExactly(new Operation("ADD", null, "PREFERENCE", "希望回答简短"));
    }

    @Test
    void contentMayContainTheSeparator() {
        assertThat(MemoryExtractionClient.parse("ADD|FACT|常用 a|b 这种写法"))
                .containsExactly(new Operation("ADD", null, "FACT", "常用 a|b 这种写法"));
    }

    @Test
    void toleratesSpacesAndFullWidthSeparators() {
        List<Operation> ops = MemoryExtractionClient.parse("""
                ADD | FACT | 喜欢打篮球
                update｜5｜FACT｜已经入职
                DELETE | #7
                """);

        assertThat(ops).containsExactly(
                new Operation("ADD", null, "FACT", "喜欢打篮球"),
                new Operation("UPDATE", 5L, "FACT", "已经入职"),
                new Operation("DELETE", 7L, null, null));
    }

    @Test
    void acceptsAtMostTenOperations() {
        assertThat(MemoryExtractionClient.parse("DELETE|1\n".repeat(30)))
                .hasSize(MemoryExtractionClient.MAX_OPERATIONS);
    }
}
