import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
        versionCode = 1
        versionName = "1.0.0"

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
        // 供 SampleDataSeeder 判定 debug/release：生产构建必须关闭演示数据播种
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
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
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation & Lifecycle for Compose
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // WorkManager：周期对账兜底（A6，兑现 FINAL-PRODUCT D-14 的第三档承诺）
    //
    // 选它而不是 AlarmManager 周期闹钟：WorkManager 自带进程唤醒与 Doze/厂商策略适配，
    // 且**不需要**前台常驻服务（§6.5 明确不引入）。详见 REMINDER-DOMAIN-REDESIGN A6。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

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

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
