package com.mcxiaoke.carromed.ui.component

import androidx.annotation.StringRes
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.model.LedgerNoteKey
import com.mcxiaoke.carromed.core.domain.model.MedicationCategory
import com.mcxiaoke.carromed.core.domain.model.MedicationForm
import com.mcxiaoke.carromed.core.domain.model.RecordNoteKey
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

    @StringRes
    fun ledgerNoteRes(key: String?): Int? = when (key) {
        LedgerNoteKey.TAKE_DEDUCT.name -> R.string.note_ledger_take_deduct
        LedgerNoteKey.SKIP.name -> R.string.note_ledger_skip
        LedgerNoteKey.REJUDGE_TAKEN.name -> R.string.note_ledger_rejudge_taken
        LedgerNoteKey.UNDO_TAKE_REVERT.name -> R.string.note_ledger_undo_revert
        LedgerNoteKey.UNDO_TEMP_REVERT.name -> R.string.note_ledger_undo_temp
        LedgerNoteKey.DOSE_EDIT.name -> R.string.note_ledger_dose_edit
        LedgerNoteKey.RETRO_DEDUCT.name -> R.string.note_ledger_retro
        LedgerNoteKey.PRN_DEDUCT.name -> R.string.note_ledger_prn
        LedgerNoteKey.CALIBRATE.name -> R.string.note_ledger_calibrate
        LedgerNoteKey.TRACKING_INIT.name -> R.string.note_ledger_track_init
        LedgerNoteKey.TRACKING_INIT_CALIBRATE.name -> R.string.note_ledger_track_init_calibrate
        LedgerNoteKey.REFILL.name -> R.string.note_ledger_refill
        else -> null
    }

    /**
     * 流水备注的显示文本：有 key → 本地化标签 + 非空载荷以「（载荷）」拼接；
     * 无 key → 用户自由文本原样；两者皆空 → null。
     * 供 Composable（LocalContext）与 CSV 导出（Context）共用，规则只有这一份。
     */
    fun ledgerNoteDisplay(context: android.content.Context, noteKey: String?, note: String?): String? {
        val payload = note?.takeIf { it.isNotBlank() }
        val res = ledgerNoteRes(noteKey)
        if (res == null) return payload
        val label = context.getString(res)
        return if (payload == null) label else "$label（$payload）"
    }

    @StringRes
    fun recordNoteRes(key: String?): Int? = when (key) {
        RecordNoteKey.NOTIFICATION_TAKE.name -> R.string.note_record_notification_take
        RecordNoteKey.NOTIFICATION_SKIP.name -> R.string.note_record_notification_skip
        RecordNoteKey.SKIP.name -> R.string.note_record_skip
        RecordNoteKey.MANUAL_BACKFILL.name -> R.string.note_record_manual_backfill
        RecordNoteKey.REJUDGE_TAKEN.name -> R.string.note_record_rejudge_taken
        else -> null
    }

    /**
     * 服药记录备注的显示文本：与 [ledgerNoteDisplay] 同规则。
     *
     * 用户可编辑备注的编辑入口（详情页）读的是**原始 note 列**，不经过这里 ——
     * 那是用户自己的文字，不该被程序化标签覆盖。
     */
    fun recordNoteDisplay(context: android.content.Context, noteKey: String?, note: String?): String? {
        val payload = note?.takeIf { it.isNotBlank() }
        val res = recordNoteRes(noteKey)
        if (res == null) return payload
        val label = context.getString(res)
        return if (payload == null) label else "$label（$payload）"
    }
}
