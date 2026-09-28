package com.mcxiaoke.carromed.core.testing

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.domain.model.Dose

/**
 * 测试用断言辅助：把「整数毫单位的原始列」与「展示值」之间的换算收敛到一处。
 *
 * ## 为什么需要它
 *
 * D-7 落地后，库存与剂量的原始列全是**整数毫单位**（1 片 = 1000），
 * 而领域层对外的 API 与 UI 状态用的是 `Float` 展示值。
 * 于是测试里会出现两类断言：
 *
 * ```kotlin
 * assertThat(inventoryDao.getSumOfChanges(id)).isEqualTo(18000)   // 原始列 → 整数
 * db.assertLedgerBalance(id, 18f)                                 // 展示值 → 浮点
 * ```
 *
 * 后者到处手写 `Dose(x).asFloat` 极易漏（A1b 改造中就连续漏了 4 处），
 * 且漏掉时报的是 `expected: 18.0 but was: 18000` 这种看不出意图的错。
 * 这里把换算封进命名清晰的 helper，让断言意图自明。
 */

/** 断言台账余额等于给定的展示值（自动做毫单位换算）。 */
suspend fun AppDatabase.assertLedgerBalance(medicationId: Long, expected: Float) {
    val actual = Dose(inventoryTransactionDao().getSumOfChanges(medicationId) ?: 0).asFloat
    assertThat(actual).isEqualTo(expected)
}

/** 断言流水的 `balanceAfter` 快照等于给定的展示值。 */
fun assertBalanceAfter(milli: Int, expected: Float) {
    assertThat(Dose(milli).asFloat).isEqualTo(expected)
}

/** 断言服药剂量的原始毫单位值。 */
fun assertDoseMilli(milli: Int, expected: Int) {
    assertThat(milli).isEqualTo(expected)
}

/** 断言服药剂量的展示值。 */
fun assertDoseValue(milli: Int, expected: Float) {
    assertThat(Dose(milli).asFloat).isEqualTo(expected)
}
