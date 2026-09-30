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
