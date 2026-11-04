package com.nick.jarvis.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 把上传的文件内容解码成字符串。
 *
 * 文本文件本身不记录自己是什么编码。现在大多是 UTF-8，但 Windows 上的老文件常常是 GBK，
 * 用 UTF-8 去读 GBK 的文件，中文会变成乱码。
 * 做法：先“严格”地按 UTF-8 解码，遇到不合法的字节就报错；报错了再按 GB18030（GBK 的超集）解码。
 * GBK 编码的中文几乎不可能恰好是合法的 UTF-8，所以这个判断很可靠。
 */
final class TextDecoder {

    private static final Charset GB18030 = Charset.forName("GB18030");

    private TextDecoder() {
    }

    static String decode(byte[] bytes) {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, GB18030);
        }
    }

    /** BOM：有些编辑器（比如 Windows 记事本）会在 UTF-8 文件开头加 3 个字节的标记，要去掉 */
    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }
}
