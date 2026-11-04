package com.nick.jarvis.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TextDecoderTest {

    @Test
    void decodesUtf8() {
        assertThat(TextDecoder.decode("你好 Jarvis".getBytes(StandardCharsets.UTF_8))).isEqualTo("你好 Jarvis");
    }

    @Test
    void fallsBackToGbkForOldWindowsFiles() {
        byte[] gbk = "你好，世界".getBytes(Charset.forName("GBK"));

        assertThat(TextDecoder.decode(gbk)).isEqualTo("你好，世界");
    }

    @Test
    void removesUtf8Bom() {
        byte[] text = "你好".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[text.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(text, 0, withBom, 3, text.length);

        assertThat(TextDecoder.decode(withBom)).isEqualTo("你好");
    }
}
