package com.mcxiaoke.carromed.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 备份文件混淆/防敏感信息泄露测试。
 */
class BackupObfuscationTest {

    @Test
    fun `obfuscateBackup 混淆后不含敏感明文且以 CMBF01 标识开头`() {
        val sensitiveJson = """{"medications":[{"name":"阿托伐他汀钙片","notes":"晚饭后服用"}],"doseRecords":[{"actualTime":"2026-10-17"}]}"""

        val obfuscated = DataExporter.obfuscateBackup(sensitiveJson)

        // 必须带有版本标识前缀
        assertThat(obfuscated).startsWith(DataExporter.OBFUSCATION_PREFIX)
        // 敏感明文完全不可见
        assertThat(obfuscated).doesNotContain("阿托伐他汀")
        assertThat(obfuscated).doesNotContain("晚饭后服用")
        assertThat(obfuscated).doesNotContain("doseRecords")
        assertThat(obfuscated).doesNotContain("2026-10-17")
    }

    @Test
    fun `deobfuscateBackup 能够完整还原混淆前的内容`() {
        val original = """{"app":"CarroMed","version":2,"medications":[{"name":"二甲双胍"}]}"""

        val obfuscated = DataExporter.obfuscateBackup(original)
        val restored = DataExporter.deobfuscateBackup(obfuscated)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `decodeBackup 兼容新版混淆备份与旧版明文 JSON`() {
        val backup = BackupFile(
            app = "CarroMed",
            formatVersion = 2,
            exportedAt = 1757000000000L,
            exportedAtText = "2026-10-17 12:00:00"
        )
        val rawJson = DataExporter.encodeBackup(backup)
        val obfuscated = DataExporter.obfuscateBackup(rawJson)

        // 1. 新版混淆格式解析
        val decodedFromObfuscated = DataExporter.decodeBackup(obfuscated)
        assertThat(decodedFromObfuscated.app).isEqualTo("CarroMed")
        assertThat(decodedFromObfuscated.exportedAtText).isEqualTo("2026-10-17 12:00:00")

        // 2. 旧版明文 JSON 格式解析（向后兼容）
        val decodedFromPlainJson = DataExporter.decodeBackup(rawJson)
        assertThat(decodedFromPlainJson.app).isEqualTo("CarroMed")
        assertThat(decodedFromPlainJson.exportedAtText).isEqualTo("2026-10-17 12:00:00")
    }
}
