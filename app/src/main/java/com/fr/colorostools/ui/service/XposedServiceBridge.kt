package com.fr.colorostools.ui.service

import android.content.Context
import android.content.SharedPreferences
import com.fr.colorostools.ui.service.XposedServiceBridge.preferences
import com.fr.colorostools.utils.IntentPrefs
import com.fr.colorostools.utils.LogUtils
import com.fr.colorostools.utils.ModulePrefs
import com.fr.colorostools.utils.OtherPrefs
import com.fr.colorostools.utils.SettingsPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 模块 App 侧接入 libxposed service 的唯一入口：
 * - 绑定成功后持有 [XposedService]
 * - [preferences] 返回 remote prefs（宿主进程 YukiHookPreferences 的同一数据源），
 *   绑定不可用（未激活/无框架）时回落 null，由调用方走本地 prefs
 */
object XposedServiceBridge {

    private val bindSignal = CountDownLatch(1)

    @Volatile
    private var initialized = false

    @Volatile
    var xposedService: XposedService? = null
        private set

    @Volatile
    private var appContext: Context? = null

    /** 搬迁完成标记（存在**本地** prefs 里，防止重复搬迁、也防止「清除全部数据」后被旧值复活） */
    private const val MIGRATED_FLAG = "__local_to_remote_migrated"

    /** 本模块用到的四个 prefs 文件名（与 SPUtils 的常量一致） */
    private val PREF_NAMES = arrayOf(ModulePrefs, IntentPrefs, SettingsPrefs, OtherPrefs)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                bindSignal.countDown()
                // 绑定完成即推送激活态：冷启动时 UI 首轮读取必然早于绑定（异步），
                // 此前只能靠用户切一次 tab 的 ON_RESUME 才刷新到 true（闪现「已停用」）
                _moduleActive.value = service.scope?.isNotEmpty() == true
                migrateLocalToRemote(service)
                LogUtils.d(
                    "Libxposed Service Bridge",
                    "bind service",
                    "API ${service.frameworkName} ${service.apiVersion}",
                    true
                )
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
                _moduleActive.value = false
            }
        })
    }

    /**
     * **一次性搬迁：本地 prefs → remote prefs。**
     *
     * 背景：`appPrefs()` 在框架绑定前走本地 prefs、绑定后走 remote prefs，而绑定是**异步**的
     * （冷启动有一段窗口期）。用户在这段窗口期里改的开关只写进了本地 prefs，绑定后统一读
     * remote 就会把这些改动**看丢** —— 表现为「开关自己弹回默认值 / 覆盖安装后自己打开」。
     *
     * 规则：
     * - 只补 remote 里**不存在**的键；remote 已有值一律不动（remote 优先，绑定后的修改不被覆盖）；
     * - 本地 flag [MIGRATED_FLAG] 保证只执行一次，避免「清除全部数据」后被旧的本地值复活。
     */
    private fun migrateLocalToRemote(service: XposedService) {
        val ctx = appContext ?: return
        Thread {
            PREF_NAMES.forEach { name ->
                runCatching {
                    val local = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                    if (local.getBoolean(MIGRATED_FLAG, false)) return@runCatching
                    val remote = service.getRemotePreferences(name) ?: return@runCatching
                    val editor = remote.edit()
                    var pending = false
                    local.all.forEach { (key, value) ->
                        if (key == MIGRATED_FLAG || remote.contains(key)) return@forEach
                        when (value) {
                            is Boolean -> { editor.putBoolean(key, value); pending = true }
                            is String -> { editor.putString(key, value); pending = true }
                            is Int -> { editor.putInt(key, value); pending = true }
                            is Long -> { editor.putLong(key, value); pending = true }
                            is Float -> {editor.putFloat(key, value); pending = true }
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                editor.putStringSet(key, (value as Set<String>).toHashSet())
                                pending = true
                            }
                        }
                    }
                    if (pending) editor.commit()
                    local.edit().putBoolean(MIGRATED_FLAG, true).commit()
                }.onFailure {
                    LogUtils.e(
                        "Libxposed Service Bridge", "migrateLocalToRemote $name",
                        "${it.javaClass.simpleName}: ${it.message}",
                    )
                }
            }
        }.start()
    }

    /** 等待服务绑定完成（无框架环境超时后保持 null） */
    fun awaitReady(timeoutMs: Long = 3000) {
        runCatching { bindSignal.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * 模块激活态（框架已绑定且 scope 列表非空）的**可观察状态**。
     *
     * 背景：libxposed service 绑定是异步的，冷启动有一段窗口期。此前 UI 只在
     * 首次组合 / ON_RESUME 轮询一次 [isModuleActive]，冷启动首轮必然读到 false
     * （闪现「模块已停用」），要等用户切到其他 tab 再回来才恢复。
     * 现在绑定完成 / 死亡时主动推送，UI 订阅后即时刷新，无需轮询、无窗口期。
     */
    private val _moduleActive = MutableStateFlow(false)
    val moduleActiveFlow: StateFlow<Boolean> = _moduleActive.asStateFlow()

    /** 模块是否处于激活态：框架已绑定且 scope 列表非空（一次性查询用；UI 订阅 [moduleActiveFlow]） */
    val isModuleActive: Boolean
        get() = xposedService?.scope?.isNotEmpty() == true

    fun preferences(name: String): SharedPreferences? =
        xposedService?.getRemotePreferences(name)
}