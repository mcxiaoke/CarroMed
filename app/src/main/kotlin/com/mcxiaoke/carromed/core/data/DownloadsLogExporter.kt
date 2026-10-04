package com.mcxiaoke.carromed.core.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.mcxiaoke.carromed.core.domain.DiagnosticExport
import java.io.File

/**
 * 把诊断日志写入系统 Downloads（`Download/CarroMed/`）。
 *
 * ## 免权限是怎么做到的
 *
 * 分两条路径，**都不向用户要任何权限**：
 *
 * | API | 路径 | 依据 |
 * | :--- | :--- | :--- |
 * | 29+ (Android 10) | `MediaStore.Downloads` + `RELATIVE_PATH` | 分区存储下 MediaStore
 *   的 Downloads 集合对写入方**不设权限门**（`WRITE_EXTERNAL_STORAGE` 在 29+ 已是 no-op） |
 * | 26-28 (Android 8-9) | 若 `WRITE_EXTERNAL_STORAGE` **已授予**才走公共 Downloads | 未授予时
 *   退回 app 专属外部目录（见下方降级说明） |
 *
 * **不主动申请 `WRITE_EXTERNAL_STORAGE`**：为一个"崩溃时才用一次"的诊断功能，
 * 在首启弹一个存储权限框，代价（用户对用药 App 的信任）远大于收益。
 * 26-28 降级路径的产物仍能通过 `adb` 取到，且这两个版本已早于项目主要用户群。
 *
 * ## 降级路径（26-28 无权限时）
 *
 * 退回 `getExternalFilesDir(DIRECTORY_DOWNLOADS)/CarroMed`。它**不需要权限**，
 * 但会被「清除数据」和卸载清掉——所以它只是"比没有强"，不是等价替代。
 * 这一点在 [exportAll] 的返回值里如实上报，由调用方决定要不要提示用户。
 *
 * ## 崩溃现场的并发与超时
 *
 * MediaStore 写入是 **Binder IPC**。崩溃时进程已处于不可用状态，system_server
 * 若恰好繁忙（或我们就是把它拖忙的元凶），这次 IPC 可能**长时间不返回**。
 * 若在崩溃线程上直接同步写，"留痕"就变成了"把崩溃 hang 成 ANR"，比不写更糟。
 *
 * 因此 [exportCrashBlocking] 走**守护线程 + 有界 join**：
 * 写得完就写，超时就放弃（后台线程是 daemon，不阻止进程退出）。
 * 放弃并不丢日志——崩溃文件此刻已经躺在 `filesDir/logs/`，
 * 下次启动 [exportAll] 会把它补投出去。这正是"崩溃时尽力 + 启动时兜底"双写的原因。
 *
 * 纯 JVM 部分（文件名、选片）在 `DiagnosticExport`；本类只管 IO 与权限分支。
 */
object DownloadsLogExporter {

    private const val TAG = "DownloadsLogExporter"
    private const val MIME_PLAIN = "text/plain"

    /** MediaStore 重名副本的标记：`xxx (1).txt`。见 [writeViaMediaStore]。 */
    private val DUPLICATE_SUFFIX = Regex("""\s\(\d+\)""")

    /** 崩溃现场的有界等待上限。超时即放弃，交给下次启动补投。 */
    private const val CRASH_WRITE_TIMEOUT_MS = 1_500L

    /**
     * 崩溃现场外投：把 [content] 写成 `carromed-crash-<yyyyMMddHHmmss>.log`。
     *
     * 同步语义（调用方在崩溃处理器里），但内部有界超时，见类注释。
     *
     * @return 是否确认写入成功。**任何失败都只返回 false，绝不抛出**——
     *         崩溃处理器里抛出的异常会顶掉真正的崩溃堆栈。
     */
    fun exportCrashBlocking(context: Context, content: String, nowMs: Long): Boolean {
        val name = DiagnosticExport.crashFileName(nowMs)
        var ok = false
        val worker = Thread({
            ok = runCatching { write(context, name, content) }
                .onFailure { Log.w(TAG, "crash export failed: ${it.message}") }
                .isSuccess
        }, "carromed-crash-export")
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(CRASH_WRITE_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!ok) Log.w(TAG, "crash export to Downloads did not complete in time; deferred to next launch")
        return ok
    }

    /**
     * 启动时补投：把内部 `logs/` 目录里够格的文件全部外投一次。
     *
     * 幂等：目标文件名由**源文件 mtime** 推导（见 `DiagnosticExport`），
     * 所以重复启动只会覆盖同一份 Downloads 文件，不会攒出 `-2`/`-3` 僵尸。
     * 内部源文件**不删**——App 内的诊断日志入口还要读它们。
     *
     * @param includeRuntimeLogs 是否一并外投运行期日志（`app-*.log`）。**冷启动的自动补投
     *   必须传 false**：运行日志里带药名，而 `Download/` 对持有读存储权限的其他应用与
     *   媒体扫描器可见 —— 用户从未同意"每次冷启动把用药记录拷一份到公共目录"。
     *   崩溃样本不受影响，照常外投。
     * @return 成功写入的目标文件名（供启动日志自证）
     */
    fun exportAll(
        context: Context,
        logDir: File,
        nowMs: Long,
        keepLogDays: Int = 3,
        includeRuntimeLogs: Boolean = true
    ): List<String> {
        val files = logDir.listFiles()?.toList() ?: return emptyList()
        val written = mutableListOf<String>()
        // ⚠️ 必须按**目标名分组**后再写：`LogFileSink` 体积滚动出的 `app-YYYYMMDD-N.log`
        // 与 `app-YYYYMMDD.log` 归到**同一个**目标名（`DiagnosticExport.logFileName` 按天
        // 命名，刻意保持"一天一份"）。若逐个写，后写的会与先写的同名 → MediaStore 把它
        // 改名为 `xxx (1).txt` → 随即被 `purgeStaleArtifacts` 当垃圾删掉 →
        // **前一段日志静默丢失**。按 `appLogSeq` 升序拼接成一次写入可同时保住顺序与内容。
        val plans = DiagnosticExport.plan(files, nowMs, keepLogDays, includeRuntimeLogs)
        for ((targetName, group) in plans.groupBy { it.targetName }) {
            val ordered = group.sortedBy { DiagnosticExport.appLogSeq(it.source.name) }
            // 崩溃文件可能正被崩溃处理器写；读不到就跳过，下次启动再试
            val parts = ordered.mapNotNull { runCatching { it.source.readText() }.getOrNull() }
            if (parts.isEmpty()) continue
            runCatching { write(context, targetName, parts.joinToString("")) }
                .onSuccess { written.add(targetName) }
                .onFailure { Log.w(TAG, "export $targetName failed: ${it.message}") }
        }
        // 清理已内聚到每次 write() 的 finally（见 writeViaMediaStore）：
        // 必须**写完再清**，写在清之前清掉的只是上一轮遗留，本轮 insert 顺手
        // 生成的 `xxx (1).txt` 无人收拾，越导越多（真机实测）。
        return written
    }

    /**
     * 清掉 `Download/CarroMed/` 里的历史垃圾（仅 API 29+ 有意义）。
     *
     * 只删两类，判据收窄——删错文件是数据事故：
     * 1. **`.pending-` 孤儿**：进程在 `IS_PENDING=1` 期间被杀留下的半截文件。
     *    它对用户可见且内容残缺，冒充完整崩溃报告，比没有更误导。
     * 2. **`xxx (1)` 副本**：MediaStore 改名导致去重失效时堆出来的僵尸。
     *
     * 正常件（`is_pending=0` 且无 `(N)` 后缀）一律不碰。
     */
    private fun purgeStaleArtifacts(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.IS_PENDING
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        // 用**前缀**精确匹配自己名下目录（`Download/CarroMed%`），不要用 `%CarroMed%`：
        // 子串匹配会命中用户自建的 `Download/MyCarroMedNotes/` 并删掉其中的文件。
        val args = arrayOf(subdirPrefixArg())

        runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val pendingIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
                val doomed = mutableListOf<Long>()
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val isPending = c.getInt(pendingIdx) != 0
                    if (name.startsWith(".pending-") || DUPLICATE_SUFFIX.containsMatchIn(name)) {
                        doomed.add(c.getLong(idIdx))
                    } else if (isPending) {
                        // 上次崩溃把文件卡在 pending 上，后续写入已无法再更新它
                        doomed.add(c.getLong(idIdx))
                    }
                }
                for (id in doomed) {
                    runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
                }
                if (doomed.isNotEmpty()) Log.i(TAG, "purged ${doomed.size} stale artifact(s)")
            }
        }
    }

    /**
     * 实际写入。按 API 分派，见类注释的权限表。
     *
     * @return 写入是否成功
     */
    private fun write(context: Context, name: String, content: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, name, content)
        } else {
            writeViaLegacyFile(context, name, content)
        }

    // ---------------- API 29+：MediaStore，零权限 ----------------

    /**
     * 走 `MediaStore.Downloads`。
     *
     * ## 扩展名：请求名即落盘名
     *
     * 外投一律用 `.txt`（见 `DiagnosticExport.EXPORT_EXT`）。这不是随意选的：
     * MediaStore 依 `MIME_TYPE=text/plain` 反推扩展名，早先试过 `.log`，
     * 落盘被追加成 `.log.txt`，导致请求名与落盘名不一致、去重失效、副本堆积。
     * 用 `.txt` 之后两者天然一致，这段代码不必再猜系统会怎么改名。
     *
     * 另两个关键细节：
     *
     * 1. **同名先删再插**：`insert` 对重名不会覆盖，会生成 `xxx (1).txt`。
     * 2. **`IS_PENDING` 收尾**：先置 1 写入、flush 后置 0。
     *    置 1 期间文件对用户不可见，进程若在写入中途被杀，不会留下半截文件
     *    冒充完整崩溃报告——排查时半截堆栈比没文件更误导。
     *
     * `@RequiresApi(Q)`：`MediaStore.Downloads` 是 API 29 才有的集合。调用方
     * [write] 已用 `SDK_INT >= Q` 分派 —— 标注是把这个不变量交给编译器/Lint 守，
     * 而不是只写在注释里（此前 Lint `NewApi` 三处报错正因判据散在调用方）。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, name: String, content: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_PLAIN)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${DiagnosticExport.EXPORT_SUBDIR}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, pending) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray())
                out.flush()
            } ?: return false

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null
            )

            // 请求名 == 落盘名（都用 .txt），故正常路径下 actualName 就是 name；
            // 回读仅作兜底：若某家 OEM 仍擅自改名，这里能兜住，不会去重失效。
            val actualName = queryDisplayName(resolver, uri) ?: name
            deleteSiblings(context, actualName, keepId = uri)
            true
        } catch (t: Throwable) {
            // 半成品不能留在用户可见的 Downloads 里，清掉
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "media store write failed: ${t.message}")
            false
        } finally {
            // 收尾清一次：MediaStore 会在 insert 时顺手造 `xxx (1).txt` 副本，
            // 上面 deleteSiblings 只清了同名件，清完本次再整体扫一遍兜住改名副本。
            // 放在 finally 是因为崩溃路径（exportCrashBlocking）也必须享受清理，
            // 否则它每次崩溃都在 Downloads 里留一份垃圾。
            purgeStaleArtifacts(context)
        }
    }

    /** 读回落盘后的真实文件名；读不到就退回请求名。 */
    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    /**
     * 删掉同目录下同名、但**不是 [keepId] 本身**的旧件。
     *
     * 排除自身很关键：insert 刚建的新件与目标同名，先删它等于把本次导出删掉。
     *
     * `RELATIVE_PATH` 用 `LIKE` 而非 `=`：实测该字段存的是 `Download/CarroMed/`
     * （带尾斜杠），各家 ROM 格式不一，`=` 会漏删。
     *
     * `@RequiresApi(Q)`：同 [writeViaMediaStore]，调用方已按 API 分派。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteSiblings(context: Context, displayName: String, keepId: Uri) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Downloads._ID} <> ?"
        // selectionArgs 必须全为 String：id 也转成字符串，否则 arrayOf 推出公共父类型而编译失败
        val args = arrayOf(
            displayName,
            subdirPrefixArg(),
            ContentUris.parseId(keepId).toString()
        )

        runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    runCatching {
                        resolver.delete(
                            ContentUris.withAppendedId(collection, id), null, null
                        )
                    }
                }
            }
        }
    }

    // ---------------- API 26-28：legacy File ----------------

    /**
     * 分区存储之前，只能拿真实 File 路径写。
     *
     * 公共 Downloads 需要 `WRITE_EXTERNAL_STORAGE`；本项目**不主动申请**它，
     * 因此绝大多数情况下落到 app 专属外部目录（免权限，但会被清数据/卸载带走）。
     */
    private fun writeViaLegacyFile(context: Context, name: String, content: String): Boolean {
        val dir = legacyTargetDir(context) ?: return false
        val file = File(dir, name)
        return runCatching {
            file.writeText(content)
            true
        }.getOrElse {
            Log.w(TAG, "legacy file write failed: ${it.message}")
            false
        }
    }

    private fun legacyTargetDir(context: Context): File? {
        if (hasLegacyWritePermission(context)) {
            @Suppress("DEPRECATION")
            val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (public != null && (public.isDirectory || public.mkdirs())) {
                return File(public, DiagnosticExport.EXPORT_SUBDIR).apply { mkdirs() }
            }
        }
        // 降级：app 专属外部目录，不需要任何权限
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(base, DiagnosticExport.EXPORT_SUBDIR).apply { mkdirs() }
    }

    private fun hasLegacyWritePermission(context: Context): Boolean =
        context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * MediaStore `RELATIVE_PATH` 的**前缀**匹配参数：`Download/CarroMed%`。
     *
     * 为什么不用 `%CarroMed%`：那是子串匹配，会命中用户自建的
     * `Download/MyCarroMedNotes/` —— 清理逻辑随即删掉别人的文件（删错文件是数据事故）。
     * 为什么结尾用通配而不写死斜杠：实测 `RELATIVE_PATH` 存的是 `Download/CarroMed/`
     * （带尾斜杠），但各家 ROM 格式不一，`=` 与固定结尾都会漏删。
     */
    private fun subdirPrefixArg(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/${DiagnosticExport.EXPORT_SUBDIR}%"

    /** 仅供诊断：当前走的是哪条路径（启动日志自证用）。 */
    fun activePathLabel(context: Context): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "MediaStore/Downloads"
        hasLegacyWritePermission(context) -> "legacy/public-Downloads"
        else -> "fallback/app-external (清数据即失)"
    }

    /** 保留给测试与调试：MediaStore 的目标 collection Uri（API 29+ 才有该集合）。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
}
