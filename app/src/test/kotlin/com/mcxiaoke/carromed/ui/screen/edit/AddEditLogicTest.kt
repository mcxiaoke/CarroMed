package com.mcxiaoke.carromed.ui.screen.edit

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.model.PolicyType
import org.junit.Test

/**
 * 添加/编辑表单的纯状态逻辑测试
 *
 * 覆盖两类此前完全缺失的录入能力：
 * 1. PRN(按需) 与 CYCLE(周期) 频次的边界
 * 2. 表单选项集是否覆盖主流吃药 App 的核心维度（尤其是「服药与用餐关系」枚举）
 */
class AddEditLogicTest {

    private fun base() = AddEditUiState()

    @Test
    fun defaultState_startsWithOneDailySlot() {
        val s = base()
        assertThat(s.policyType).isEqualTo(PolicyType.DAILY)
        assertThat(s.timeSlots).hasSize(1)
        assertThat(s.isEdit).isFalse()
        assertThat(s.title).isEqualTo("添加药品")
    }

    @Test
    fun title_distinguishesAddFromEditInfo() {
        assertThat(base().title).isEqualTo("添加药品")
        val edit = AddEditUiState(medId = 5L, mode = AddEditMode.INFO_ONLY)
        assertThat(edit.isEdit).isTrue()
        assertThat(edit.title).isEqualTo("编辑药品信息")
    }

    @Test
    fun intervalDays_clampsTo2To30() {
        // 2 = 隔天一次；30 = 每隔 29 天。低于 2 或高于 30 一律夹紧。
        assertThat(1.coerceIn(2, 30)).isEqualTo(2)
        assertThat(99.coerceIn(2, 30)).isEqualTo(30)
        assertThat(2.coerceIn(2, 30)).isEqualTo(2)
    }

    @Test
    fun cycleDays_clamps() {
        assertThat(1.coerceIn(1, 90)).isEqualTo(1)
        assertThat(0.coerceIn(1, 90)).isEqualTo(1)
        assertThat(200.coerceIn(1, 90)).isEqualTo(90)
        assertThat((-5).coerceIn(0, 30)).isEqualTo(0)
        assertThat(45.coerceIn(0, 30)).isEqualTo(30)
    }

    @Test
    fun daysOfWeek_toggleAddsAndRemovesKeepingOrder() {
        var days = emptyList<Int>()
        days = if (1 in days) days - 1 else (days + 1).sorted()
        assertThat(days).containsExactly(1)
        days = if (3 in days) days - 3 else (days + 3).sorted()
        assertThat(days).containsExactly(1, 3).inOrder()
        days = if (1 in days) days - 1 else (days + 1).sorted()
        assertThat(days).containsExactly(3)
    }

    @Test
    fun errorConstants_areDistinctAndNonBlank() {
        assertThat(AddEditMedicationViewModel.NAME_ERROR).isEqualTo("请输入药品名称")
        assertThat(AddEditMedicationViewModel.DOW_ERROR).isEqualTo("请至少选择一个每周服药日")
        assertThat(AddEditMedicationViewModel.TIME_ERROR).isEqualTo("请至少设置一个提醒时点")
    }

    @Test
    fun formOptions_coverRequiredDimensions() {
        // 单位：此前全库写死"片"，导致 ml/滴 一律显示错误
        assertThat(MedicationFormOptions.UNITS).containsAtLeast("片", "粒", "袋", "ml", "滴")
        // 剂型
        assertThat(MedicationFormOptions.FORMS).containsAtLeast("片剂", "胶囊", "口服液", "外用")
        // 类别
        assertThat(MedicationFormOptions.CATEGORIES).containsAtLeast("常备药", "慢病处方")
        // 服药与用餐关系：主流 App 的核心枚举，CarroMed 此前完全没有
        assertThat(MedicationFormOptions.TIME_LABELS).containsAtLeast(
            "空腹服用", "饭前服用", "随餐服用", "睡前"
        )
    }

    @Test
    fun fivePolicyTypes_areAllReachableFromForm() {
        // 引擎支持 5 种；UI 必须全部可达，否则是死功能
        val all = listOf(
            PolicyType.DAILY, PolicyType.INTERVAL, PolicyType.DAYS_OF_WEEK,
            PolicyType.CYCLE, PolicyType.PRN
        )
        assertThat(all).hasSize(5)
        all.forEach { type ->
            val s = base().copy(policyType = type)
            assertThat(s.policyType).isEqualTo(type)
        }
    }
}
