package com.zaijian.zhoumuyun.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * [detectFileCharset] / [detectCharsetFromBytes] 编码检测单元测试
 * （v148：4096 字节采样截断误判 bug 修复的回归测试）。
 *
 * 覆盖矩阵：
 *  - BOM 检测：UTF-8 / UTF-16LE / UTF-16BE
 *  - 空内容 → 回退 UTF-8
 *  - 纯 ASCII、短中文 UTF-8（远小于 4096 字节）→ 正确判定 UTF-8
 *  - 核心回归用例：>4096 字节的中文 UTF-8 内容在 6 种字节偏移下都应正确判定为
 *    UTF-8（旧实现在偏移 1/2/4/5 时会把合法 UTF-8 误判为 GBK，偏移 0/3 恰好
 *    字节对齐所以旧实现也凑巧判对——这正是原 bug 报告里手工验证的 6 种偏移）
 *  - 真实 GBK 编码内容仍应正确判定为 GBK（确认没有矫枉过正）
 *  - [detectFileCharset] 对真实文件的端到端读写往返验证
 *  - [detectCharsetFromBytes] 的 len 参数（模拟 InputStream 只读到部分字节、
 *    数组尾部是垃圾数据的场景，对应 ProjectRepository.importFile 的用法）
 */
class CharsetDetectionTest {

    // ═══════════════════════════════════════════════════════════
    //  BOM 检测
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `UTF-8 BOM is detected`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, detectCharsetFromBytes(bytes))
    }

    @Test
    fun `UTF-16LE BOM is detected`() {
        val utf16le = Charset.forName("UTF-16LE")
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "你好".toByteArray(utf16le)
        assertEquals(utf16le, detectCharsetFromBytes(bytes))
    }

    @Test
    fun `UTF-16BE BOM is detected`() {
        val utf16be = Charset.forName("UTF-16BE")
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "你好".toByteArray(utf16be)
        assertEquals(utf16be, detectCharsetFromBytes(bytes))
    }

    // ═══════════════════════════════════════════════════════════
    //  空内容 / 纯 ASCII / 短中文
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `empty byte array falls back to UTF-8`() {
        assertEquals(Charsets.UTF_8, detectCharsetFromBytes(ByteArray(0)))
    }

    @Test
    fun `pure ASCII content is detected as UTF-8`() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, detectCharsetFromBytes(bytes))
    }

    @Test
    fun `short Chinese UTF-8 content well under 4096 bytes is detected correctly`() {
        val bytes = "这是一段远小于4KB的中文测试内容，用来验证短文本不受影响。".toByteArray(Charsets.UTF_8)
        assertTrue("短中文内容应远小于 4096 字节", bytes.size < 4096)
        assertEquals(Charsets.UTF_8, detectCharsetFromBytes(bytes))
    }

    // ═══════════════════════════════════════════════════════════
    //  核心回归：4096 字节采样边界不再误判（v148 bug）
    // ═══════════════════════════════════════════════════════════

    /**
     * 复现原 bug 报告里的手工验证过程：同一段中文内容，只是开头多加 0~5 个
     * ASCII 字符作为前缀，导致每个中文字符（UTF-8 下 3 字节）相对 4096 边界
     * 的字节对齐偏移不同。旧实现（截断样本 + 找 U+FFFD 替换符）在偏移
     * 1/2/4/5 时会把合法 UTF-8 误判为 GBK；新实现全部 6 种偏移都应正确判定
     * 为 UTF-8。
     */
    @Test
    fun `UTF-8 detection is stable across all byte alignments at the 4096-byte boundary`() {
        // 10 个中文字符 = 30 字节（3 的倍数），重复 1000 次远超 4096 字节，
        // 且大量 3 字节字符连续排列，4096 边界几乎必然落在某个字符中间。
        val body = "中文编码检测边界测试".repeat(1000)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        require(bodyBytes.size > 4096) { "测试前提：正文应超过 4096 字节，实际 ${bodyBytes.size}" }

        for (offset in 0..5) {
            val prefix = "a".repeat(offset)
            val bytes = (prefix + body).toByteArray(Charsets.UTF_8)
            val detected = detectCharsetFromBytes(bytes, minOf(bytes.size, 4096))
            assertEquals(
                "偏移 $offset 字节时应正确判定为 UTF-8（旧实现在偏移 1/2/4/5 下会误判为 GBK）",
                Charsets.UTF_8,
                detected,
            )
        }
    }

    /**
     * 端到端版本：实际写一个 >4096 字节的中文 UTF-8 markdown 文件到磁盘，
     * 用 [detectFileCharset] 检测并读回，验证不会重现"预览/agent 读取双双
     * 乱码"的问题（FilePreviewParser 与 FileReadTool 共用同一个检测函数）。
     */
    @Test
    fun `detectFileCharset correctly identifies a large UTF-8 markdown file`() {
        val tmpDir = Files.createTempDirectory("charsetTest").toFile()
        try {
            val file = File(tmpDir, "notes.md")
            val content = "# 标题\n\n" + "这是一段用于测试的中文正文内容。".repeat(500)
            require(content.toByteArray(Charsets.UTF_8).size > 4096)
            file.writeText(content, Charsets.UTF_8)

            val detected = detectFileCharset(file)
            assertEquals("大于 4096 字节的中文 UTF-8 md 文件应正确判定为 UTF-8", Charsets.UTF_8, detected)
            assertEquals("用检测到的编码读回应与原文一致（未乱码）", content, file.readText(detected))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  真实 GBK 内容仍应判定为 GBK（不能矫枉过正）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `real GBK-encoded content is still detected as GBK`() {
        val gbk = Charset.forName("GBK")
        val bytes = "这是一段模拟 Windows Excel 导出的 GBK 编码内容，重复以确保超过采样长度。".repeat(50).toByteArray(gbk)
        // 前提校验：这段字节本身确实不是合法 UTF-8（否则这条测试没有意义）
        assertTrue(
            "测试前提：GBK 字节不应恰好也是合法 UTF-8",
            String(bytes, Charsets.UTF_8).contains('\uFFFD'),
        )
        assertEquals(gbk, detectCharsetFromBytes(bytes, minOf(bytes.size, 4096)))
    }

    /**
     * 端到端版本：模拟 Windows Excel 导出的 GBK 编码 CSV 文件，验证
     * [detectFileCharset] 正确识别为 GBK 而不是错误回退成 UTF-8。
     */
    @Test
    fun `detectFileCharset correctly identifies a GBK csv file end to end`() {
        val tmpDir = Files.createTempDirectory("charsetTest").toFile()
        try {
            val file = File(tmpDir, "legacy.csv")
            val gbk = Charset.forName("GBK")
            val content = "姓名,年龄,城市\n张三,28,北京\n李四,32,上海\n".repeat(200)
            require(content.toByteArray(gbk).size > 4096)
            file.writeText(content, gbk)

            val detected = detectFileCharset(file)
            assertEquals("GBK 编码的 CSV 文件应正确判定为 GBK", gbk, detected)
            assertEquals("用检测到的编码读回应与原文一致", content, file.readText(detected))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  detectCharsetFromBytes 的 len 参数
    //  （对应 ProjectRepository.importFile：InputStream 读到的字节数组）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `detectCharsetFromBytes respects explicit len shorter than array size`() {
        // 模拟数组尾部是未使用的垃圾数据（全 0），只有前 real.size 字节有效
        val real = "你好世界，这是一段用于验证 len 参数的中文内容。".toByteArray(Charsets.UTF_8)
        val buffer = ByteArray(4096)
        System.arraycopy(real, 0, buffer, 0, real.size)
        assertEquals(Charsets.UTF_8, detectCharsetFromBytes(buffer, real.size))
    }
}
