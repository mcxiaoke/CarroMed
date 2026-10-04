# CarroMed R8/ProGuard 规则
#
# 背景：`app/build.gradle.kts` 的 release 构建用 `proguardFiles(...)` 引用了本文件，
# 但此前文件并不存在 —— 因为 `isMinifyEnabled = false` 而不报错，
# 一旦有人打开混淆，构建立即失败。
#
# 当前 release **未开启混淆/裁剪**（`isMinifyEnabled = false`），
# 因此本文件在现状下不参与任何处理；它的作用是让"打开混淆"这件事不再因缺文件而崩。
# 真正开启混淆前，请补全下述规则的验证（尤其 Room / kotlinx.serialization / Compose）。

# Room 生成的实现类通过反射加载，保留实体与 DAO 的公开构造与签名。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# kotlinx.serialization：数据类序列化元数据
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 崩溃堆栈可读性（Release 也需要能定位问题）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
