package com.mcxiaoke.carromed.core.data.model

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import com.mcxiaoke.carromed.core.domain.model.Dose
import java.time.LocalDate
import org.junit.Test

/**
 * [MedicationOverview] 的量纲契约测试。
 *
 * ## 这条契约是被真实 bug 逼出来的
 *
 * A1b 把金额列从 `Float` 改成整数毫单位（D-7）之后，`MedicationEntity.minStockAlert`
 * 变成了 `15000` 而不是 `15f`。药箱页当时写的是
 * `stock <= med.minStockAlert && med.minStockAlert > 0f`，
 * 于是 `50f <= 15000` 恒成立 —— **4 个药全部误报低库存**，
 * 而同一时刻库存页显示"余量 50 / 预警线 15"（正确）。
 *
 * 编译器抓不到这个错误：Kotlin 允许 `Float <= Int`（提升为 `Float <= Float`）。
 * 能抓到的只有**类型设计** —— `MedicationOverview` 上只暴露 Float 展示值，
 * 读实体那一份就必须显式 `Dose(x).asFloat`，量纲转换被迫显式。
 *
 * 下面的测试把这条契约钉死。
 */
class MedicationOverviewTest {

    private fun overview(
        stock: Int,
        minStockAlert: Int = 0,
        isStockTracked: Boolean = true,
    ) = MedicationOverview(
        medication = MedicationEntity(
            id = 1L,
            name = "测试药",
            unit = "片",
            minStockAlert = minStockAlert,
            isStockTracked = isStockTracked
        ),
        stock = Dose(stock).asFloat
    )

    @Test
    fun `minStockAlert 代理返回展示值而不是毫单位`() {
        // 这是本测试类的核心断言
        assertThat(overview(stock = 50000, minStockAlert = 15000).minStockAlert).isEqualTo(15f)
    }

    @Test
    fun `低库存判定用展示值比较时不会被毫单位撑爆`() {
        val o = overview(stock = 50000, minStockAlert = 15000)  // 50 片 / 预警线 15 片
        // 50 片 > 15 片预警线 ⇒ **不应**告警
        assertThat(o.stock <= o.minStockAlert).isFalse()
    }

    @Test
    fun `确实低于预警线时判定为告警`() {
        val o = overview(stock = 6000, minStockAlert = 15000)   // 6 片 / 预警线 15 片
        assertThat(o.stock <= o.minStockAlert).isTrue()
    }

    @Test
    fun `预警线为 0 表示关闭低库存告警`() {
        // minStockAlert = 0 的语义是"关闭告警"，不是"库存必须为 0 才告警"
        val o = overview(stock = 0, minStockAlert = 0)
        assertThat(o.minStockAlert).isEqualTo(0f)
        // 调用方需额外要求 alert > 0f
        assertThat(o.minStockAlert > 0f).isFalse()
    }

    @Test
    fun `负库存照常透传 不被截断（D-9）`() {
        val o = overview(stock = -500)
        assertThat(o.stock).isEqualTo(-0.5f)
        assertThat(o.stock < 0f).isTrue()
    }

    @Test
    fun `代理字段透传实体字段`() {
        val o = overview(stock = 10000, minStockAlert = 10000, isStockTracked = true)
        assertThat(o.id).isEqualTo(1L)
        assertThat(o.name).isEqualTo("测试药")
        assertThat(o.unit).isEqualTo("片")
        assertThat(o.isStockTracked).isTrue()
        // 暂停是派生量，不是字段（A2）。未暂停 ⇒ isPausedOn(anyDate) == false
        assertThat(o.isPausedOn(LocalDate.now())).isFalse()
        assertThat(o.isArchived).isFalse()
    }

    @Test
    fun `实体仍然是毫单位 便于断言存储口径`() {
        val o = overview(stock = 0, minStockAlert = 12500)
        assertThat(o.medication.minStockAlert).isEqualTo(12500)          // 存储口径
        assertThat(Dose(o.medication.minStockAlert).asFloat).isEqualTo(12.5f)  // 展示口径
        assertThat(o.minStockAlert).isEqualTo(12.5f)                    // 代理已换算
    }
}
