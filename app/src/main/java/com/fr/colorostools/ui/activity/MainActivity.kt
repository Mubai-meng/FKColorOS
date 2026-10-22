package com.fr.colorostools.ui.activity

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.highcapable.betterandroid.system.extension.component.Intent
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.kavaref.extension.classOf
import com.fr.colorostools.R
import com.fr.colorostools.ui.compose.FunctionRequest
import com.fr.colorostools.ui.compose.ColorOSToolsSplashHost
import com.fr.colorostools.ui.compose.MainShell
import com.fr.colorostools.ui.compose.components.GateDialogSpec
import com.fr.colorostools.ui.theme.ColorOSToolsAppTheme
import com.fr.colorostools.utils.A12
import com.fr.colorostools.utils.BiometricUtils
import com.fr.colorostools.utils.DeviceUtils
import com.fr.colorostools.utils.IntentPrefs
import com.fr.colorostools.utils.ModulePrefs
import com.fr.colorostools.utils.OtherPrefs
import com.fr.colorostools.utils.PermissionUtils
import com.fr.colorostools.utils.SDK
import com.fr.colorostools.utils.SettingsPrefs
import com.fr.colorostools.utils.ThemeUtils
import com.fr.colorostools.utils.getBoolean
import com.fr.colorostools.utils.getOSVersionCode
import com.fr.colorostools.utils.putBoolean
import com.fr.colorostools.utils.verityPackage
import kotlinx.coroutines.flow.MutableStateFlow
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Suppress("PrivatePropertyName")
open class MainActivity : AppCompatActivity() {
    //检测Prefs状态
    private val KEY_PREFIX = classOf<MainActivity>().name + '.'
    private val EXTRA_SAVED_INSTANCE_STATE = KEY_PREFIX + "SAVED_INSTANCE_STATE"

    /** 当前是否位于 Function tab（由 MainShell 维护，供 onResume 显示恢复） */
    var isOnFunctionTab by mutableStateOf(false)

    /** 当前是否位于 Home tab（冷启动默认 true = 起始目的地；checkOs 判断用） */
    var isOnHomeTab by mutableStateOf(true)

    /** 跨 tab 跳转请求队列：Compose 页面 → Function 子树作用域页（MainShell 切 tab，FunctionPage 消费） */
    val functionNavRequests = MutableStateFlow<FunctionRequest?>(null)

    /** 启动阻塞门对话框（无 root / 系统不支持）：非空即由 MainShell 渲染，见 GateDialog */
    var gateDialog by mutableStateOf<GateDialogSpec?>(null)

    private fun newIntent(context: Context): Intent {
        return Intent<MainActivity>(context)
    }

    private fun newIntent(savedInstanceState: Bundle, context: Context): Intent {
        return newIntent(context).putExtra(EXTRA_SAVED_INSTANCE_STATE, savedInstanceState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeUtils.initTheme(this)

        verityPackage()
        // 不再拦截「模块未激活」：未接入 LSPosed 时也应能正常打开模块（仅主页状态卡置灰提示）
        checkOs()
        checkUserId()
        checkBiometric()

        setContent {
            ColorOSToolsAppTheme {
                // 启动闪屏覆盖在主界面之上：主界面同时组合，闪屏淡出后立即可用
                // 启动阻塞门是场景内弹层，闪屏淡出后即露出，不需要为它跳过闪屏
                ColorOSToolsSplashHost {
                    MainShell(this)
                }
            }
        }
    }

    /** Compose 页面跨 tab 跳转到 Function 子树作用域页（pageKey 见 ScopePageRegistry） */
    fun requestFunctionNavigation(pageKey: String, title: CharSequence?) {
        functionNavRequests.value = FunctionRequest(pageKey, title?.toString())
    }

    private fun checkUserId() {
        if ((Process.myUid() / 100000) != 0) {
            toast(getString(R.string.check_app_userid))
            finish()
        }
    }

    private fun checkSu() {
        val isSu = DeviceUtils.getRootStatus()
        putBoolean(SettingsPrefs, "is_su", isSu)
        putBoolean(SettingsPrefs, "settings_prefs", isSu)
        putBoolean(ModulePrefs, "module_prefs", isSu)
        putBoolean(IntentPrefs, "intent_prefs", isSu)
        putBoolean(OtherPrefs, "other_prefs", isSu)
        if (!isSu && gateDialog == null) {
            gateDialog = GateDialogSpec(
                title = getString(R.string.no_root),
                message = getString(R.string.no_root_summary),
            )
            return
        }
        // 注：上游这里每次 onResume 都强制写 enable_module_print_logs = BuildConfig.DEBUG，
        // 属于会把开关「自己写开」的死键（本模块日志总开关用的是 enable_log），已删除。
        PermissionUtils(this).start()
    }

    private fun checkOs() {
        val osCode = getOSVersionCode
        if (osCode < 23 && isOnHomeTab) {
            gateDialog = GateDialogSpec(
                title = getString(R.string.unsupported_os),
                message = getString(R.string.unsupported_os_summary),
                // 与迁移前一致：osCode > 0 时额外提供「忽略」，点击只关闭对话框
                neutralLabel = if (osCode > 0) getString(R.string.ignore) else null,
            )
        }
    }

    private fun checkBiometric() {
        val enable = getBoolean(SettingsPrefs, "enable_biometric_unlock_verification", false)
        val manager = getSystemService(classOf<KeyguardManager>())
        if (enable && manager.isDeviceSecure) {
            BiometricUtils.showBiometricPrompt(
                this,
                onError = { _, _ -> finish() },
                onFailed = { finish() })
        }
    }

    // 壳子阶段：控制器服务（GlobalFunc/Packages/Tiles/RefreshRate/Adb/ActivityManager/User/Power）
    // 属于具体功能能力，待对应 hook 功能接入时再补 init。

    override fun onResume() {
        super.onResume()
        checkSu()
    }

    @Suppress("DEPRECATION")
    fun restart() {
        if (SDK >= A12 || !Process.isApplicationUid(Process.myUid())) {
            recreate()
        } else {
            try {
                val savedInstanceState = Bundle()
                onSaveInstanceState(savedInstanceState)
                finish()
                startActivity(newIntent(savedInstanceState, this))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (_: Throwable) {
                recreate()
            }
        }
    }
}
