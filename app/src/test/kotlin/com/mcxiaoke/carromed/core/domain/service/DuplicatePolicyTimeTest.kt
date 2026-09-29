package com.mcxiaoke.carromed.core.domain.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 同一计划内重复时点的拒绝测试（治代码审查 zcg 报告 P1）。
 *
 * ## 缺陷链（每一环都已在代码里核实）
 *
 * 1. `saveReminderPolicy` 对 `times` 不做任何去重或查重（对比：同函数里
 *    `daysOfWeek` 做了 `distinct()`）；
 * 2. `SlotProjectionEngine.projectSlots` 对每个时点各投影一条 ⇒ 同一
 *    `(date, "08:30")` 产出两条、`doseAmount` 各自不同；
 * 3. `DoseSlotDao.insertAll` 是 `IGNORE`，撞上
 *    `(medication_id, scheduled_date, scheduled_time)` 的 UNIQUE 索引后
 *    **第二条被静默丢弃**，无日志无异常。
 *
 * 后果：用户设的两个 08:00（各带剂量）只剩一条，另一条的剂量从此不存在——
 * 提醒少响一次、库存少扣一份，且 `policy_times` 里那行永不物化。
 * `validateBackup` 对备份里的同类错误有专门拦截（`DUPLICATE_SLOT_KEY`），
 * 生产写入路径却放行——校验层与写入层对同一件事的判断相反。
 *
 * ## 本测试钉的是"拒绝"方案
 *
 * 修法二选一（见审查报告 §一.1）：
 * - **拒绝**：`saveReminderPolicy` 入口 `require` 时点互不相同（本测试的断言）；
 * - **合并**：同刻时点的剂量求和后物化为一条。
 *
 * 若产品拍板选"合并"，本测试会一直红——届时请把它**改写**成
 * "同刻时点合并后剂量之和"的断言，而不是删掉：无论选哪边，
 * "静默吞并"都必须有测试看着。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DuplicatePolicyTimeTest {

    private lateinit var db: AppDatabase
    private lateinit var admin: MedicationAdminService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        admin = MedicationAdminService(db)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    @Test
    fun `同一计划里两个相同时点 必须被拒绝而不是静默吞并`() = runTest {
        val medId = admin.saveProfile(
            MedicationAdminService.ProfileDraft(name = "重复时点药", unit = "片")
        )

        val error = runCatching {
            admin.saveReminderPolicy(
                medicationId = medId,
                draft = MedicationAdminService.PolicyDraft(
                    startDate = "2026-10-10",
                    times = listOf(
                        MedicationAdminService.TimeDraft("08:00", 1f, "早间"),
                        MedicationAdminService.TimeDraft("08:00", 2f, "另加一剂")
                    )
                )
            )
        }.exceptionOrNull()

        // ★ 保存必须失败（并因此整条事务回滚）。当前实现静默成功：
        //   两条 policy_times 落库，投影后 UNIQUE 索引吞掉第二条槽位，
        //   "另加一剂"的 2 片从此不存在于任何提醒、任何打卡、任何台账里。
        assertThat(error).isNotNull()

        // 拒绝必须原子：不能留下"半保存"的计划
        assertThat(db.schedulePolicyDao().getActivePolicyForMedication(medId)).isNull()
        assertThat(db.schedulePolicyDao().getAllTimes()).isEmpty()
    }
}
