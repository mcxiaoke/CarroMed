package com.mcxiaoke.carromed.core.domain.model

/**
 * 药品词表的稳定 key（B3：类别 / 剂型 / 时段标签）。
 *
 * 存库值一律是这里的枚举 [name]，显示文案由 `res/values/strings_vocab.xml`
 * 按 key 映射（`category_common` / `form_tablet` / `slot_generic` …）。
 * 此前这三处是中文自由文本，双语后不同 locale 存库值会分叉；
 * 收敛为 key 后，存库值与 locale 解耦，旧中文文案只存在于备份兼容与显示层。
 *
 * 本文件位于 `core/domain`，零 `android.*` 依赖，
 * `MedicationAdminService` 等领域服务可直接引用。
 */
enum class MedicationCategory { COMMON, CHRONIC, RX_IMMUNE, ANTIBIOTIC, HORMONE, SUPPLEMENT, OTHER }

enum class MedicationForm { TABLET, CAPSULE, SOFTGEL, GRANULE, ORAL_LIQUID, TOPICAL, DROPS, SPRAY, PATCH }

enum class SlotLabel { GENERIC, FASTING, BEFORE_MEAL, WITH_MEAL, AFTER_MEAL, BEDTIME }

/**
 * 库存流水程序化备注的分类 key（B5，PLAN-I18N-20260930 §3 D-C）。
 *
 * 约定：`InventoryTransactionEntity.noteKey` 存这里的枚举 [name]，
 * `note` 列只放**数据载荷**（数量对、用户附加文本），不放任何界面文案；
 * 显示与 CSV 导出按 key 走 `note_ledger_*` 资源，载荷以「（载荷）」形式拼接。
 * `noteKey == null` 表示用户自由文本，原样显示。
 */
enum class LedgerNoteKey {
    /** 打卡扣减（含通知栏快捷打卡） */
    TAKE_DEDUCT,

    /** 主动跳过（不扣库存时也可能有说明性流水） */
    SKIP,

    /** 改判为已服 */
    REJUDGE_TAKEN,

    /** 误触打卡撤销冲正 */
    UNDO_TAKE_REVERT,

    /** 临时/按需服药记录撤销冲正 */
    UNDO_TEMP_REVERT,

    /** 修改剂量差额调整 */
    DOSE_EDIT,

    /** 事后补录扣减 */
    RETRO_DEDUCT,

    /** 按需/临时服药扣减 */
    PRN_DEDUCT,

    /** 库存盘点校准 */
    CALIBRATE,

    /** 开启库存追踪建档 */
    TRACKING_INIT,

    /** 开启库存追踪建档校准 */
    TRACKING_INIT_CALIBRATE,

    /** 采购入库补货 */
    REFILL
}

/**
 * 服药记录程序化备注的分类 key（B5）。
 *
 * 与 [LedgerNoteKey] 同构：`DoseRecordEntity.noteKey` 存 [name]，
 * `note` 只放用户/数据文本；显示走 `note_record_*` 资源。
 * 通知栏快捷打卡/跳过也走这里 —— 否则 `getString` 的结果会被写进库，
 * 切到英文后同一种操作在历史里留下另一种语言的文案。
 */
enum class RecordNoteKey {
    /** 通知栏快捷打卡 */
    NOTIFICATION_TAKE,

    /** 通知栏快捷跳过 */
    NOTIFICATION_SKIP,

    /** 主动跳过本次服药（UI 触发） */
    SKIP,

    /** 手动补录服药 */
    MANUAL_BACKFILL,

    /** 改判为已服（跳过 → 已服） */
    REJUDGE_TAKEN
}

