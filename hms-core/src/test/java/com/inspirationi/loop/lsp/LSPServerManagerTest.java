package com.inspirationi.loop.lsp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LSPServerManagerTest {

    /**
     * 注册诊断时用的是 URI 转换出来的路径，清空已交付记录时用的是
     * {@code Path.of(...).toUri()}。两种写法不是同一个字符串，清除永远落空 ——
     * 文件改好并保存后，旧诊断仍然被当作「已交付」，再也不会重新上报。
     */
    @Test
    void diagnosticKeyMatchesForPathAndUriForms() {
        String path = Path.of("src", "main", "A.java").toAbsolutePath().toString();

        assertEquals(LSPServerManager.diagnosticKey(path),
                LSPServerManager.diagnosticKey(Path.of(path).toUri().toString()),
                "同一文件的路径形式与 URI 形式必须映射到同一个键");
    }

    /** 带空格与中文的路径：URI 会被百分号编码，解码后必须回到原路径。 */
    @Test
    void diagnosticKeyDecodesPercentEncodedUri() {
        Path file = Path.of("my dir", "中文.java").toAbsolutePath();

        assertEquals(file.toString(), LSPServerManager.diagnosticKey(file.toUri().toString()));
    }
}
