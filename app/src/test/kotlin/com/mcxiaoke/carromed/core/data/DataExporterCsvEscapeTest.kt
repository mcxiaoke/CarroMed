package com.mcxiaoke.carromed.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `DataExporter.escapeCsv` 字段转义 + 公式注入防护测试（T-1 / orsbf P1-19）。
 *
 * 备注、药品名、注意事项是用户自由文本，导出的 CSV 会被 Excel / WPS 直接打开：
 * 以 `=` `+` `-` `@`（及 Tab/裸回车）开头的单元格会被当**公式执行** ——
 * 轻则原值消失（`=1+1` → 2），重则渲染成可点击的钓鱼链接或触发命令执行。
 * 防护 = 双引号包裹（转义逗号/引号/换行）+ 值前置单引号（强制按文本求值）。
 *
 * 纯 JVM 测试：被测函数零依赖，不需要 Robolectric。
 */
class DataExporterCsvEscapeTest {

    // ---------------- 公式注入防护 ----------------

    @Test
    fun `等号开头的表达式被前置单引号并加双引号包裹`() {
        assertThat(DataExporter.escapeCsv("=1+1")).isEqualTo("\"'=1+1\"")
    }

    @Test
    fun `加号开头的文本同样触发防护`() {
        assertThat(DataExporter.escapeCsv("+SUM(A1)")).isEqualTo("\"'+SUM(A1)\"")
    }

    @Test
    fun `负号开头一律加防护（剂量等场景已在上游保证不出现）`() {
        assertThat(DataExporter.escapeCsv("-1+1")).isEqualTo("\"'-1+1\"")
    }

    @Test
    fun `@ 开头（HYPERLINK 或 cmd 类钓鱼载荷）触发防护`() {
        assertThat(DataExporter.escapeCsv("@SUM(1)")).isEqualTo("\"'@SUM(1)\"")
    }

    @Test
    fun `防护只看首个非空白字符 前导空白被剥掉而非保留`() {
        // `' =1+1` 在部分解析器下 trim 后仍按公式求值 —— 留着前导空白等于防护失效
        assertThat(DataExporter.escapeCsv(" \t=1+1")).isEqualTo("\"'=1+1\"")
    }

    @Test
    fun `中间出现等号不算公式 不触发防护`() {
        assertThat(DataExporter.escapeCsv("a=b")).isEqualTo("a=b")
    }

    @Test
    fun `普通中文文本原样输出`() {
        assertThat(DataExporter.escapeCsv("随餐温水送服")).isEqualTo("随餐温水送服")
    }

    @Test
    fun `空串与纯空白不触发公式防护`() {
        assertThat(DataExporter.escapeCsv("")).isEmpty()
        // trimStart 后为空 ⇒ needsFormulaGuard = false；无引号字符 ⇒ 原样
        assertThat(DataExporter.escapeCsv("   ")).isEqualTo("   ")
    }

    // ---------------- 常规字段转义 ----------------

    @Test
    fun `含逗号加双引号包裹`() {
        assertThat(DataExporter.escapeCsv("早饭后,准时服药")).isEqualTo("\"早饭后,准时服药\"")
    }

    @Test
    fun `内嵌双引号被双写转义`() {
        assertThat(DataExporter.escapeCsv("他说\"注意\"")).isEqualTo("\"他说\"\"注意\"\"\"")
    }

    @Test
    fun `换行与裸回车均触发引号包裹`() {
        assertThat(DataExporter.escapeCsv("第一行\n第二行")).isEqualTo("\"第一行\n第二行\"")
        assertThat(DataExporter.escapeCsv("裸回车\r文本")).isEqualTo("\"裸回车\r文本\"")
    }

    @Test
    fun `公式前缀与特殊字符叠加时引号内值仍带单引号`() {
        // 公式防护 + 内嵌引号双写：两道处理叠加在同一个 safe 值上
        assertThat(DataExporter.escapeCsv("=a\"b")).isEqualTo("\"'=a\"\"b\"")
    }
}
