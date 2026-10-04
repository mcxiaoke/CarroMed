package com.mcxiaoke.carromed.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 主题模式的判据（3-5 / 3-6 深色配色要能被验证的前提）。
 *
 * 刻意做成**纯逻辑测试**：`resolveDark` 与 `fromStorage` 都不碰 Android API，
 * 系统是否深色由参数注入。
 *
 * 为什么这两条值得测：
 * - `resolveDark` 一旦写反（比如 LIGHT 返回 true），表现是"用户选了浅色却还是深色"——
 *   真机上很容易被误判成"主题没生效"，而不是"判据写反了"；
 * - `fromStorage` 是唯一读持久化的入口，脏数据必须回落到 SYSTEM ——
 *   若这里抛异常，App 会在冷启动的 `ThemePreference.init` 里直接崩，
 *   而那时用户什么都看不到。
 */
class ThemeModeTest {

    @Test
    fun `跟随系统 直接取系统值`() {
        assertThat(ThemeMode.SYSTEM.resolveDark(systemInDarkTheme = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.resolveDark(systemInDarkTheme = false)).isFalse()
    }

    @Test
    fun `强制浅色 忽略系统为深色`() {
        assertThat(ThemeMode.LIGHT.resolveDark(systemInDarkTheme = true)).isFalse()
        assertThat(ThemeMode.LIGHT.resolveDark(systemInDarkTheme = false)).isFalse()
    }

    @Test
    fun `强制深色 忽略系统为浅色`() {
        assertThat(ThemeMode.DARK.resolveDark(systemInDarkTheme = true)).isTrue()
        assertThat(ThemeMode.DARK.resolveDark(systemInDarkTheme = false)).isTrue()
    }

    @Test
    fun `从存储还原 正常值`() {
        ThemeMode.entries.forEach { mode ->
            assertThat(ThemeMode.fromStorage(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `从存储还原 大小写不敏感`() {
        assertThat(ThemeMode.fromStorage("dark")).isEqualTo(ThemeMode.DARK)
        assertThat(ThemeMode.fromStorage("Light")).isEqualTo(ThemeMode.LIGHT)
    }

    @Test
    fun `从存储还原 空值与脏数据回落跟随系统`() {
        assertThat(ThemeMode.fromStorage(null)).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStorage("")).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStorage("AMOLED")).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStorage("1")).isEqualTo(ThemeMode.SYSTEM)
    }
}
