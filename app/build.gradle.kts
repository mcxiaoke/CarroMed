import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("io.github.takahirom.roborazzi")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
val hasReleaseKey = keystorePropertiesFile.exists()
if (hasReleaseKey) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.mcxiaoke.carromed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcxiaoke.carromed"
        minSdk = 26
        targetSdk = 35
        versionCode = 100001
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) }
                storePassword = keystoreProperties.getProperty("storePassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey && keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 供 BuildConfig.DEBUG 判定构建类型：日志 Logcat 挂载、debug 源集入口、
        // destructive migration 仅在 debug 开启
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

/**
 * Room schema 落盘位置（A-16 / §二-16）。
 *
 * `exportSchema = true` 只有在配置了 `room.schemaLocation` 时才真正写文件 ——
 * 否则 KSP 只会在编译期抛"Schema export directory is not provided"警告并对
 * 每个 schema 变更再喊一次。落盘到 `app/schemas/`，作为将来重建迁移的真相起点。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * 单元测试跑在 JUnit 5 Platform 上。
 *
 * 为什么要切：属性化测试（jqwik）只支持 JUnit 5，而现存 65 项 JUnit 4 + Robolectric
 * 测试必须继续可跑 —— Vintage Engine 让两套并存，不必冒 Robolectric JUnit 5 支持
 * 不完整的风险做全量迁移。
 *
 * 迁移背景见 docs/REMINDER-DOMAIN-REDESIGN.md §5.4。
 */
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // 进程级生命周期（ON_START/ON_STOP）—— "今天是几号" 的前台翻转检测依赖它。
    // 不用它就得自己数 Activity 引用 / 猜前台状态，那是另一处会静默失效的机制。
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation & Lifecycle for Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // WorkManager：周期对账兜底（A6，兑现 FINAL-PRODUCT D-14 的第三档承诺）
    //
    // 选它而不是 AlarmManager 周期闹钟：WorkManager 自带进程唤醒与 Doze/厂商策略适配，
    // 且**不需要**前台常驻服务（§6.5 明确不引入）。详见 REMINDER-DOMAIN-REDESIGN A6。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // kotlinx.serialization：备份格式（A4）
    //
    // 替换掉原来约 300 行手写 `JSONObject.put` / `optString`。
    // 关键收益不是"少写样板"，而是让**字段遗漏变成可测的**：
    // 旧实现的 `appSettings` 只导出了 key/value，`updatedAt` 恢复即永久丢失，
    // 而没有任何测试会发现 —— 手写映射没有 schema，两个方向都要人肉同步。
    // 详见 REMINDER-DOMAIN-REDESIGN §6.4。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Room 2.6.1 (SQLite ORM & In-Memory Database)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Local JVM Unit Tests & Robolectric
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.google.truth:truth:1.4.4")

    // WorkManager 测试支持：ReconcileWorker 的排期参数（间隔下限 / 约束 / 退避）必须被断言，
    // 其中「约束必须为空」最关键 —— App 物理断网（无 INTERNET 权限），
    // 一旦有人误加 NetworkType.CONNECTED，Worker 会永远不执行且**没有任何报错**。
    testImplementation("androidx.work:work-testing:2.9.1")

    // 属性化测试：覆盖 docs/REMINDER-DOMAIN-REDESIGN.md §4 的不变量 I3/I5/I6/I7/I8。
    // 选用 jqwik（JVM 原生、成熟、报告可读）而非自造随机生成器。
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.jqwik:jqwik:1.9.2")
    // 让现存 JUnit 4 + Robolectric 测试继续在 JUnit 5 Platform 上被发现与执行
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    // Roborazzi 视觉回归（PLAN-UI-TEST-20260929.md P2）：跑在 Robolectric 里，
    // 直接进现有 testDebugUnitTest 循环，零新增环境。
    // ⚠️ 版本必须与根插件的 1.40.1 一致（1.72.0 的 Kotlin 2.3 元数据不兼容本项目）。
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.40.1")
    // Robolectric 里跑 createComposeRule / createAndroidComposeRule 需要 ui-test 全家桶
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Compose UI 冒烟测试（PLAN-UI-TEST-20260929.md P1，堵 §2 坑 5：
    // viewModel 工厂反射路径只有 instrumented 测试能覆盖）。
    // BOM 已在上方 composeBom 管理 ui-test-junit4 的版本，无需再写版本号。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // 滚动到折叠线以下的 Compose 节点（LazyColumn 未滚到的项不在语义树里）
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.truth:truth:1.4.4")
}
