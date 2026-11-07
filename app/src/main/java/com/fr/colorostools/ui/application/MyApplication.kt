package com.fr.colorostools.ui.application

import android.app.Application
import com.fr.colorostools.BuildConfig
import com.fr.colorostools.ui.service.XposedServiceBridge
import com.fr.colorostools.ui.shell.ShellSettingsController
import com.fr.colorostools.utils.LogUtils
import com.fr.colorostools.utils.PredictiveBackUtils
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.getBoolean
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 模块日志开关（设置页「启用详细日志」，默认关）：
        // 开启后所有级别都进 LSPosed 日志页；关闭时只有错误日志。
        // App 进程不在注入范围，拿不到 XposedInterface —— LogUtils 会自动退回 logcat。
        LogUtils.enable = getBoolean(SettingsPrefs, "enable_log", false)
        LogUtils.i(
            "App", "onCreate",
            "FKColorOS ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) 启动，详细日志=${LogUtils.enable}",
        )
        XposedServiceBridge.init(this)
        // 预测性返回手势：把偏好同步到 ApplicationInfo（对齐 KernelSU 启动期行为，best-effort）
        PredictiveBackUtils.apply(this, ShellSettingsController.get(this).predictiveBack)
    }

}


























