package com.mcxiaoke.carromed.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 本机备份 File 链路测试（T-2 / sba P1-15 残留）。
 *
 * `BackupRoundTripTest` 守的是 buildBackup/restore 的**数据语义**，
 * `BackupResilienceTest` 守的是 SAF Uri 路径的**校验判据**——但用户直接
 * 导回本机导出目录文件的这条 File 链路（`listLocalBackups` /
 * `inspectLocalBackup` / `importLocalBackup` / 恢复前安全快照）此前零覆盖。
 * 链路上的每一环都值得单独钉住：
 * - `listLocalBackups` 依赖 `exportDir` 的目录状态与过滤规则；
 * - `inspectLocalBackup` 走的是 `readText(File)` 重载（与 Uri 路径不同的代码路径）；
 * - `importLocalBackup` 的 Success 快照是"误操作可逆"承诺的落点。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DataExporterLocalBackupFileTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // 目录状态是本组测试的被测对象，逐用例清空隔离
        DataExporter.listLocalBackups(context).forEach { it.file.delete() }
    }

    @After
    fun tearDown() {
        DataExporter.listLocalBackups(context).forEach { it.file.delete() }
    }

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private suspend fun seedMed(db: AppDatabase, name: String): Long =
        db.medicationDao().insert(MedicationEntity(name = name, unit = "片"))

    private fun exportDir(): File =
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
            .resolve("exports")

    // ---------------- listLocalBackups ----------------

    @Test
    fun `导出目录不存在时返回空列表而非崩溃`() {
        exportDir().deleteRecursively()
        assertThat(DataExporter.listLocalBackups(context)).isEmpty()
    }

    @Test
    fun `只列出 json 文件且按修改时间倒序`() {
        val dir = exportDir().apply { mkdirs() }
        val older = File(dir, "backup_old.json").apply {
            writeText("{}")
            setLastModified(1_000_000L)
        }
        val newer = File(dir, "backup_new.json").apply {
            writeText("{}")
            setLastModified(2_000_000L)
        }
        File(dir, "notes.txt").writeText("非 json 不该出现")

        val list = DataExporter.listLocalBackups(context)

        assertThat(list.map { it.file.name }).containsExactly("backup_new.json", "backup_old.json").inOrder()
        assertThat(list.map { it.file }).containsExactly(newer, older).inOrder()
    }

    // ---------------- inspectLocalBackup（File 重载） ----------------

    @Test
    fun `inspectLocalBackup 有效备份返回计数与文件名`() = runTest {
        val db = newDb()
        seedMed(db, "阿司匹林")
        val file = DataExporter.exportFullBackupJson(context, db)
        db.close()

        val preview = DataExporter.inspectLocalBackup(context, file).getOrThrow()

        assertThat(preview.fileName).isEqualTo(file.name)
        assertThat(preview.medicationCount).isEqualTo(1)
        assertThat(preview.recordCount).isEqualTo(0)
    }

    @Test
    fun `inspectLocalBackup 垃圾文件与非 CarroMed 备份均被拒`() = runTest {
        val garbage = File(exportDir().apply { mkdirs() }, "garbage.json")
            .apply { writeText("这不是 JSON {{{") }
        val foreign = File(exportDir(), "foreign.json")
            .apply { writeText("""{"app":"OtherApp","formatVersion":1,"medications":[]}""") }

        val garbageResult = DataExporter.inspectLocalBackup(context, garbage)
        val foreignResult = DataExporter.inspectLocalBackup(context, foreign)

        assertThat(garbageResult.isFailure).isTrue()
        assertThat(foreignResult.isFailure).isTrue()
    }

    @Test
    fun `inspectLocalBackup 文件不可读时被拒`() {
        val missing = File(exportDir().apply { mkdirs() }, "missing.json")
        assertThat(DataExporter.inspectLocalBackup(context, missing).isFailure).isTrue()
    }

    // ---------------- importLocalBackup（File 链路 + 快照） ----------------

    @Test
    fun `importLocalBackup 往返恢复且快照文件真实存在`() = runTest {
        val dbA = newDb()
        seedMed(dbA, "甲药")
        seedMed(dbA, "乙药")
        val backup = DataExporter.exportFullBackupJson(context, dbA)
        dbA.close()

        val dbB = newDb() // 目标库为空库
        val result = DataExporter.importLocalBackup(context, dbB, backup)

        val success = result as DataExporter.RestoreResult.Success
        assertThat(success.medications).isEqualTo(2)
        // 快照是"选错文件可反悔"的承诺：路径必须非空且文件真实落盘
        assertThat(success.snapshotFile).isNotNull()
        assertThat(File(success.snapshotFile!!).exists()).isTrue()
        assertThat(dbB.medicationDao().getAllMedications().map { it.name })
            .containsExactly("甲药", "乙药")
        dbB.close()
    }

    @Test
    fun `importLocalBackup 带 BOM 的 File 同样被剥头恢复`() = runTest {
        val dbA = newDb()
        seedMed(dbA, "BOM药")
        val json = DataExporter.buildBackup(dbA).let { DataExporter.encodeBackup(it) }
        dbA.close()
        val bomFile = File(exportDir().apply { mkdirs() }, "bom_backup.json")
            .apply { writeText("\uFEFF$json") }

        val dbB = newDb()
        val result = DataExporter.importLocalBackup(context, dbB, bomFile)

        assertThat(result).isInstanceOf(DataExporter.RestoreResult.Success::class.java)
        assertThat(dbB.medicationDao().getAllMedications().single().name).isEqualTo("BOM药")
        dbB.close()
    }

    @Test
    fun `importLocalBackup 非法文件返回 Invalid 且目标库分毫未动`() = runTest {
        val dbB = newDb()
        seedMed(dbB, "原有药")
        val garbage = File(exportDir().apply { mkdirs() }, "garbage.json")
            .apply { writeText("{{{ not a backup") }

        val result = DataExporter.importLocalBackup(context, dbB, garbage)

        assertThat(result).isInstanceOf(DataExporter.RestoreResult.Invalid::class.java)
        assertThat(dbB.medicationDao().getAllMedications().single().name).isEqualTo("原有药")
        dbB.close()
    }
}
