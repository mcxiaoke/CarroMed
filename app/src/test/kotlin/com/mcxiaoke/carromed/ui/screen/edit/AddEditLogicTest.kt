package com.mcxiaoke.carromed.ui.screen.edit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.model.PolicyType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 添加/编辑表单的纯状态逻辑测试
 *
 * ## 历史（P1-12 的 4 项反例，2026-09-29 测试审计 zcg 报告 §二 重写）
 *
 * 本文件曾有 4 项"充数"测试：两项断言的是 **Kotlin 标准库 `coerceIn`**
 * （被测代码根本没被调用），一项在测试里**内联复刻**了星期的开关逻辑，
 * 一项只断言枚举自身——它们无论实现怎么坏都不会红，却一直在给"302 项"贡献数字。
 * 现已全部重写为调用 [AddEditMedicationViewModel] 真实 setter 的状态断言；
 * 若将来再往本文件加测试，请先问一句："实现坏成什么样能让它变红？"
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AddEditLogicTest {

    /** medId = null ⇒ FULL 模式（新增），init 不触发任何加载 */
    private fun newVm(): AddEditMedicationViewModel = AddEditMedicationViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        medId = null
    )

    @Test
    fun defaultState_startsWithOneDailySlot() {
        val s = newVm().uiState.value
        assertThat(s.policyType).isEqualTo(PolicyType.DAILY)
        assertThat(s.timeSlots).hasSize(1)
        assertThat(s.isEdit).isFalse()
        assertThat(s.title).isEqualTo("添加药品")
    }

    @Test
    fun title_distinguishesAddFromEditInfo() {
        assertThat(newVm().uiState.value.title).isEqualTo("添加药品")
        val edit = AddEditUiState(medId = 5L, mode = AddEditMode.INFO_ONLY)
        assertThat(edit.isEdit).isTrue()
        assertThat(edit.title).isEqualTo("编辑药品信息")
    }

    /** 走真实 `onIntervalDaysChange`：夹紧发生在 ViewModel 里，不是标准库的自言自语 */
    @Test
    fun intervalDays_clampsTo2To30() {
        val vm = newVm()
        vm.onIntervalDaysChange(1)
        assertThat(vm.uiState.value.intervalDays).isEqualTo(2)
        vm.onIntervalDaysChange(99)
        assertThat(vm.uiState.value.intervalDays).isEqualTo(30)
        vm.onIntervalDaysChange(7)
        assertThat(vm.uiState.value.intervalDays).isEqualTo(7)
    }

    /** 走真实 `onCycleDaysChange` / `onCycleOffDaysChange` */
    @Test
    fun cycleDays_clamps() {
        val vm = newVm()
        vm.onCycleDaysChange(0)
        assertThat(vm.uiState.value.cycleOnDays).isEqualTo(1)
        vm.onCycleDaysChange(200)
        assertThat(vm.uiState.value.cycleOnDays).isEqualTo(90)
        vm.onCycleOffDaysChange(-5)
        assertThat(vm.uiState.value.cycleOffDays).isEqualTo(0)
        vm.onCycleOffDaysChange(45)
        assertThat(vm.uiState.value.cycleOffDays).isEqualTo(30)
    }

    /** 走真实 `onToggleDayOfWeek`：表单初始自带 [1,3,5]，移除/新增/排序都以它为基准 */
    @Test
    fun daysOfWeek_toggleAddsAndRemovesKeepingOrder() {
        val vm = newVm()
        assertThat(vm.uiState.value.daysOfWeek).containsExactly(1, 3, 5).inOrder()

        vm.onToggleDayOfWeek(3)
        assertThat(vm.uiState.value.daysOfWeek).containsExactly(1, 5).inOrder()

        // 新增 2 必须落在排序位上，而不是追加到尾部
        vm.onToggleDayOfWeek(2)
        assertThat(vm.uiState.value.daysOfWeek).containsExactly(1, 2, 5).inOrder()

        vm.onToggleDayOfWeek(1)
        assertThat(vm.uiState.value.daysOfWeek).containsExactly(2, 5).inOrder()
    }

    @Test
    fun errorConstants_areDistinctAndNonBlank() {
        assertThat(AddEditMedicationViewModel.NAME_ERROR).isEqualTo("请输入药品名称")
        assertThat(AddEditMedicationViewModel.DOW_ERROR).isEqualTo("请至少选择一个每周服药日")
        assertThat(AddEditMedicationViewModel.TIME_ERROR).isEqualTo("请至少设置一个提醒时点")
    }

    // ==================== PRN 口径必须与提醒页一致（M7-9） ====================

    /**
     * 「按需服用」不要求提醒时点 —— 两个保存入口必须给同一个答案。
     *
     * 领域层 `SlotProjectionEngine` 对 PRN 直接 `return emptyList()`，
     * 也就是说 PRN 压根不产生槽位，提醒页 `ReminderSettingsViewModel`
     * 用的判据是 `policyType != PRN && times.isEmpty()`。
     * 本文件曾经的判据多了一个 `PRN` 分支（与上一行体完全相同），
     * 于是同一份 PRN 配置**换个入口就换一套校验规则**。
     *
     * 断言 `error == null`：本测试只关心校验口径，不碰落库。
     * 真正写库的路径由字段保留不变量测试守。
     */
    @Test
    fun prn_doesNotRequireTimeSlots() {
        val vm = newVm()
        vm.onNameChange("止痛药")
        vm.onPolicyTypeChange(PolicyType.PRN)
        // 清空时点：PRN 下应当放行
        vm.updateTimeSlot(0, time = "")
        assertThat(vm.uiState.value.timeSlots.single().time).isEmpty()

        vm.save(onSuccess = {})
        assertThat(vm.uiState.value.error).isNull()
    }

    /** 反例：非 PRN 空时点**必须**被拦下，否则上一条测试就没有区分力 */
    @Test
    fun daily_stillRejectsEmptyTimeSlots() {
        val vm = newVm()
        vm.onNameChange("降压药")
        vm.updateTimeSlot(0, time = "")
        assertThat(vm.uiState.value.timeSlots.single().time).isEmpty()

        vm.save(onSuccess = {})
        assertThat(vm.uiState.value.error).isEqualTo(AddEditMedicationViewModel.TIME_ERROR)
    }

    /**
     * 槽位**存在但时点为空**必须被拦下 —— 这是 `isEmpty()` 判据漏掉的洞。
     *
     * 列表里有一条 `time = ""` 时 `timeSlots.isEmpty()` 返回 false，
     * 旧校验放行，空时点写进 `policy_times`，随后被投影层回退成 08:00。
     * 用户清空时间框、App 安静地排了每天 08:00 的闹钟。
     */
    @Test
    fun daily_rejectsSingleSlotWithBlankTime() {
        val vm = newVm()
        vm.onNameChange("降压药")
        vm.updateTimeSlot(0, time = "")
        assertThat(vm.uiState.value.timeSlots).hasSize(1)   // 列表非空

        vm.save(onSuccess = {})
        // 一条都没填 ⇒ 与"列表为空"是同一种处境（用户还没开始配提醒），
        // 所以给的是概括台词 `TIME_ERROR`，而不是逐条点名。
        assertThat(vm.uiState.value.error).isEqualTo(AddEditMedicationViewModel.TIME_ERROR)
    }

    /** 半截输入（"08:"）同样非法，且要指出是哪一格 */
    @Test
    fun daily_rejectsPartialTimeAndNamesTheSlot() {
        val vm = newVm()
        vm.onNameChange("降压药")
        vm.addTimeSlot()
        vm.updateTimeSlot(0, time = "08:00")
        vm.updateTimeSlot(1, time = "19:")
        assertThat(vm.uiState.value.timeSlots).hasSize(2)

        vm.save(onSuccess = {})
        assertThat(vm.uiState.value.error).contains("第 2 个")
        assertThat(vm.uiState.value.error).contains("19:")
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

    /** 五种频次都必须能通过真实 `onPolicyTypeChange` 设上去（此前只断言了枚举自身） */
    @Test
    fun fivePolicyTypes_areAllReachableFromForm() {
        val vm = newVm()
        for (type in listOf(
            PolicyType.DAILY, PolicyType.INTERVAL, PolicyType.DAYS_OF_WEEK,
            PolicyType.CYCLE, PolicyType.PRN
        )) {
            vm.onPolicyTypeChange(type)
            assertThat(vm.uiState.value.policyType).isEqualTo(type)
        }
    }
}
