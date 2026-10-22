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
        // 文件日志通道（MediaStore.Downloads）需要 ContentResolver：App 进程最早在此注入
        LogUtils.appContext = this
        // 模块日志总开关（设置页「启用模块日志」，默认关）：
        // 开启后输出 logcat / LSPosed 日志 / 文件；关闭时仅把错误写入 LSPosed 日志
        LogUtils.enable = getBoolean(SettingsPrefs, "enable_log", false)
        // 启动即落一行：开开关后立刻打开日志页也能看到内容（否则要等框架异步绑定完才有第一行）
        LogUtils.i(
            "App", "onCreate",
            "ColorOSTools ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) 启动，日志总开关=${LogUtils.enable}",
        )
        XposedServiceBridge.init(this)
        // 预测性返回手势：把偏好同步到 ApplicationInfo（对齐 KernelSU 启动期行为，best-effort）
        PredictiveBackUtils.apply(this, ShellSettingsController.get(this).predictiveBack)
    }

}


























