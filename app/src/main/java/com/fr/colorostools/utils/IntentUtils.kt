package com.fr.colorostools.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.annotation.DeprecatedSinceApi
import com.fr.colorostools.data.AppIntentInfo
import com.fr.colorostools.enums.IntentType
import com.fr.colorostools.enums.IntentType.CONTENT
import com.fr.colorostools.enums.IntentType.FILE
import com.fr.colorostools.enums.IntentType.HTTPS_LINK
import com.fr.colorostools.enums.IntentType.HTTP_LINK
import com.fr.colorostools.enums.IntentType.MULTI_SHARE
import com.fr.colorostools.enums.IntentType.PROCESS_TEXT
import com.fr.colorostools.enums.IntentType.SINGLE_SHARE
import com.fr.colorostools.enums.IntentType.UNKNOWN
import com.topjohnwu.superuser.ShellUtils
import org.lsposed.lsparanoid.Obfuscate


@Obfuscate
class IntentUtils(val context: Context) {

    companion object {

        /**
         * 获取Intent过滤器
         * @param types IntentType
         * @return (AppIntentInfo) -> Boolean
         */
        fun getIntentFilter(vararg types: IntentType): (AppIntentInfo) -> Boolean {
            return if (types.isEmpty()) {
                { false }
            } else {
                {
                    types.any { type ->
                        when (type) {
                            SINGLE_SHARE -> it.action == Intent.ACTION_SEND
                            MULTI_SHARE -> it.action == Intent.ACTION_SEND_MULTIPLE
                            PROCESS_TEXT -> it.action == Intent.ACTION_PROCESS_TEXT
                            CONTENT -> it.action == Intent.ACTION_VIEW && it.type == CONTENT
                            FILE -> it.action == Intent.ACTION_VIEW && it.type == FILE
                            HTTP_LINK -> it.action == Intent.ACTION_VIEW && it.type == HTTP_LINK
                            HTTPS_LINK -> it.action == Intent.ACTION_VIEW && it.type == HTTPS_LINK
                            UNKNOWN -> false
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查是否有应用支持CREATE_DOCUMENT
     * @return Boolean
     */
    fun checkCreateDocument(): Boolean {
        val packageManager = PackageUtils(context.packageManager)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.setType("application/json")
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos.isNotEmpty()
    }

    /**
     * 跳转工程模式
     */
    fun jumpEngineermode() {
        val packInfo = PackageUtils(context.packageManager).getInstalledPackages(0)
            .find { it.packageName.contains("engineermode") } ?: return
        val packName = packInfo.packageName
        val isMain = context.checkResolveActivity(
            Intent().setClassName(packName, "${packName}.EngineerModeMain")
        )
        val activity = if (isMain) "EngineerModeMain" else "aftersale.AfterSalePage"
        ShellUtils.fastCmd("am start -n ${packName}/.$activity")
    }

    /**
     * 跳转充电测试
     */
    fun jumpBatteryInfo() {
        val packInfo = PackageUtils(context.packageManager).getInstalledPackages(
            PackageManager.GET_ACTIVITIES
        ).find { it.packageName.contains("engineermode") } ?: return
        val packName = packInfo.packageName
        val activity = packInfo.activities?.find {
            it.name.contains("EngineerFragmentContainer")
        }?.name
        val chargeTestClazz = "$packName.aftersale.manualtest.ASChargeTestFragmentCompat"
        if (activity == null) LogUtils.e(
            "jumpBatteryInfo", "activity", "EngineerFragmentContainer is null", true
        ) else ShellUtils.fastCmd("am start -n $packName/$activity -e fragment $chargeTestClazz")
    }

    /**
     * 跳转到设置开发者选项页面
     */
    fun jumpSettingsDev() {
        try {
            context.startActivity(Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS").apply {
                setPackage("com.android.settings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            val command = "am start -a com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转到系统界面调节工具
     */
    fun jumpSystemUIDemoMode() {
        try {
            context.startActivity(Intent().apply {
                setPackage("com.android.systemui")
                setClassName("com.android.systemui", "com.android.systemui.DemoMode")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            val command = "am start -n com.android.systemui/.DemoMode"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转应用分身
     */
    fun jumpMultiApp() {
        if (context.checkPackName("com.oplus.multiapp")) {
            val command = "am start com.oplus.multiapp/.ui.entry.ActivityMainActivity"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转暗色模式
     */
    fun jumpDarkMode() {
        Intent("com.android.settings.DISPLAY_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(this)
        }
    }

    /**
     * 跳转到软件更新
     */
    fun jumpOTA() {
        if (context.checkPackName("com.oplus.ota")) {
            val command = "am start com.oplus.ota/com.oplus.otaui.activity.EntryActivity"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转到乐划锁屏设置页面
     */
    fun jumpPictorial() {
        if (context.checkPackName("com.heytap.pictorial")) {
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.heytap.pictorial")
                setClassName("com.heytap.pictorial", "com.heytap.pictorial.ui.SettingActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                context.startActivity(this)
            }
        }
    }

    /**
     * 跳转到手势体感页面
     */
    fun jumpGesture() {
        if (context.checkPackName("com.oplus.gesture")) {
            val command = "am start com.oplus.gesture/.guide.GestureMainActivity"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转电池性能模式
     */
    fun jumpHighPerformance() {
        if (context.checkPackName("com.oplus.battery")) {
            val command =
                "am start com.oplus.battery/com.oplus.powermanager.fuelgaue.IntellPowerSaveScence"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转到电池
     */
    fun jumpBattery() {
        if (context.checkPackName("com.oplus.battery")) {
            val command =
                "am start com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"
            ShellUtils.fastCmd(command)
        }
    }

    /**
     * 跳转进程管理
     */
    fun jumpRunningApp() {
        val packInfo = PackageUtils(context.packageManager).getPackageInfo(
            "com.android.settings", PackageManager.GET_ACTIVITIES
        ) ?: return
        val activity = packInfo.activities?.find {
            it.name.contains("RunningApplicationActivity")
        }?.name
        if (activity == null) LogUtils.e(
            "jumpRunningApp", "activity", "RunningApplicationActivity is null", true
        )
        else ShellUtils.fastCmd("am start -n com.android.settings/$activity")
    }

    /**
     * 跳转极暗模式
     */
    fun jumpVeryDarkMode() {
        Intent("android.settings.REDUCE_BRIGHT_COLORS_SETTINGS").apply {
            setPackage("com.android.settings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(this)
        }
    }

    /**
     * 跳转移动网络
     */
    fun jumpMobileNetwork() {
        Intent("android.settings.NETWORK_OPERATOR_SETTINGS").apply {
            val id = SubscriptionManager.getDefaultDataSubscriptionId()
            if (id != -1) putExtra("android.provider.extra.SUB_ID", id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(this)
        }
    }

    /**
     * 启动后台挂机服务(听剧模式)
     */
    @DeprecatedSinceApi(Build.VERSION_CODES.VANILLA_ICE_CREAM, "仅支持在C14.1及以下使用")
    fun startBackgroundRunServiceV14() {
        try {
            Intent("oplus.intent.action.BACKGROUND_STREAM_SERVICE").apply {
                setPackage("com.oplus.exsystemservice")
                component = ComponentName(
                    "com.oplus.exsystemservice",
                    "com.oplus.backgroundstream.RouteForegroundService"
                )
                context.startForegroundService(this)
            }
        } catch (e: Exception) {
            LogUtils.e(
                "startBackgroundRunService",
                "startForegroundService Exception -> ${context.packageName}!",
                e.toString(), true
            )
        }
    }
}