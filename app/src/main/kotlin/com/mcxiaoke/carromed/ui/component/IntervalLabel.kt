package com.mcxiaoke.carromed.ui.component

import android.content.Context
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.model.IntervalCadence

/**
 * INTERVAL 频次文案的**唯一实现**（ocsbf P1-5 / §一-7）。
 *
 * 语义裁决在 [IntervalCadence]（domain 层），这里只做「口径 → 文案」的映射。
 * 详情页 / 库存页 / 药箱页三处此前各写一份 `when` 分支，其中详情页的
 * 「n <= 2 隔天」口径把 `n == 1`（每天）标成了「隔天」，与实际排班相反 ——
 * 修复方式是消除重复而不是修对第三份拷贝。
 *
 * 共用文案在 `strings_vocab.xml`（`freq_interval_*`），
 * 各页旧的 `*_freq_everyday` / `*_freq_day_*` / `mdetail_sum_freq_interval*` 已删除。
 */
fun intervalLabel(context: Context, intervalDays: Int): String = when (IntervalCadence.of(intervalDays)) {
    IntervalCadence.EVERY_DAY -> context.getString(R.string.freq_interval_everyday)
    IntervalCadence.EVERY_OTHER_DAY -> context.getString(R.string.freq_interval_alternate)
    IntervalCadence.EVERY_N_DAYS -> context.getString(R.string.freq_interval_every_n_days, intervalDays)
}
