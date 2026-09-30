plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // 备份格式改用 kotlinx.serialization（A4）。版本必须与 app 模块的
    // kotlin-serialization-json 一致，否则编译器插件与运行库版本错配会在
    // 序列化时报一个极难定位的异常。
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // Roborazzi 视觉回归（PLAN-UI-TEST-20260929.md P2）：提供
    // recordRoborazziDebug / verifyRoborazziDebug 任务。
    // ⚠️ 用 1.40.1（2025-01）而不是更新的 1.72.0：后者用 Kotlin 2.3 编译，
    // 元数据在本项目 Kotlin 2.0.21 下直接拒绝编译（真实踩过）。
    // 升级本插件前先确认其 Kotlin 元数据版本 ≤ 2.1。
    id("io.github.takahirom.roborazzi") version "1.40.1" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
