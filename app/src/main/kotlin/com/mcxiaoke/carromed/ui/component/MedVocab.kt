package com.mcxiaoke.carromed.ui.component

import androidx.annotation.StringRes
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.model.MedicationCategory
import com.mcxiaoke.carromed.core.domain.model.MedicationForm
import com.mcxiaoke.carromed.core.domain.model.SlotLabel

/**
 * 词表 key → 显示资源的统一映射。
 *
 * ## 为什么需要这一层
 *
 * 类别 / 剂型 / 时段标签以**稳定 key**（枚举 name）存库（B3 key 化，见
 * PLAN-I18N-20260930 §3 D-B），显示时按 locale 取资源。所有展示点都从这里
 * 取 `@StringRes`，不允许各自 `when` 一遍 —— 否则新增一个 key 要改 N 处，漏一处
 * 就把 key 原文（如 `COMMON`）渲染给用户。
 *
 * 返回 null = 不是已知 key（自由文本/旧数据），调用方原样显示原始字符串。
 */
object MedVocab {

    @StringRes
    fun categoryRes(key: String?): Int? = when (key) {
        MedicationCategory.COMMON.name -> R.string.category_common
        MedicationCategory.CHRONIC.name -> R.string.category_chronic
        MedicationCategory.RX_IMMUNE.name -> R.string.category_rx_immune
        MedicationCategory.ANTIBIOTIC.name -> R.string.category_antibiotic
        MedicationCategory.HORMONE.name -> R.string.category_hormone
        MedicationCategory.SUPPLEMENT.name -> R.string.category_supplement
        MedicationCategory.OTHER.name -> R.string.category_other
        else -> null
    }

    @StringRes
    fun formRes(key: String?): Int? = when (key) {
        MedicationForm.TABLET.name -> R.string.form_tablet
        MedicationForm.CAPSULE.name -> R.string.form_capsule
        MedicationForm.SOFTGEL.name -> R.string.form_softgel
        MedicationForm.GRANULE.name -> R.string.form_granule
        MedicationForm.ORAL_LIQUID.name -> R.string.form_oral_liquid
        MedicationForm.TOPICAL.name -> R.string.form_topical
        MedicationForm.DROPS.name -> R.string.form_drops
        MedicationForm.SPRAY.name -> R.string.form_spray
        MedicationForm.PATCH.name -> R.string.form_patch
        else -> null
    }

    @StringRes
    fun slotLabelRes(key: String?): Int? = when (key) {
        SlotLabel.GENERIC.name -> R.string.slot_generic
        SlotLabel.FASTING.name -> R.string.slot_fasting
        SlotLabel.BEFORE_MEAL.name -> R.string.slot_before_meal
        SlotLabel.WITH_MEAL.name -> R.string.slot_with_meal
        SlotLabel.AFTER_MEAL.name -> R.string.slot_after_meal
        SlotLabel.BEDTIME.name -> R.string.slot_bedtime
        else -> null
    }
}
