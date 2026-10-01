package com.mcxiaoke.carromed.ui.screen.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.AppLog

/**
 * 主流 Android 厂商（小米、华为、OPPO、vivo、三星、魅族等）自启动与后台保护设置页面跳转助手。
 *
 * 若匹配到对应厂商的自启动/权限管理组件且系统能解析，则直达该页面；
 * 否则优雅兜底跳转到系统的“应用信息”页面（Settings.ACTION_APPLICATION_DETAILS_SETTINGS）。
 */
object VendorIntentHelper {

    private const val TAG = "VendorIntentHelper"

    fun openAutoStartOrAppDetails(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidateIntents = getCandidateIntents(context, manufacturer)

        for (intent in candidateIntents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    AppLog.i(TAG, "successfully launched vendor settings: ${intent.component ?: intent.action}")
                    return
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "failed to launch candidate intent", t)
            }
        }

        // 兜底：跳转到标准应用详情页
        openAppDetails(context)
    }

    private fun getCandidateIntents(context: Context, manufacturer: String): List<Intent> {
        val pkg = context.packageName
        val intents = mutableListOf<Intent>()

        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                // 小米自启动管理
                intents += Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
                intents += Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
                intents += Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.securityadd.showapi.Firewall")
                )
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                // 华为自启动/应用启动管理
                intents += Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                // OPPO / Realme / OnePlus ColorOS
                intents += Intent().setComponent(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                )
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                // vivo / iQOO Funtouch / OriginOS
                intents += Intent().setComponent(
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                )
            }
            manufacturer.contains("samsung") -> {
                // 三星智能管理器 / 电池管理
                intents += Intent().setComponent(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                )
                intents += Intent().setComponent(
                    ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
                )
            }
            manufacturer.contains("meizu") -> {
                // 魅族权限与自启动
                intents += Intent().setComponent(
                    ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
                ).putExtra("packageName", pkg)
            }
        }
        return intents
    }

    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(
                context, R.string.perm_open_settings_failed, Toast.LENGTH_LONG
            ).show()
        }
    }
}
