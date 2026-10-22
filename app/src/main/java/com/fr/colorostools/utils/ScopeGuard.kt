package com.fr.colorostools.utils

import android.content.Context
import com.fr.colorostools.ui.service.XposedServiceBridge
import io.github.libxposed.service.XposedService

/**
 * 作用域门禁（scope gate）。
 *
 * 背景：模块的每个 hook 功能都依赖某个宿主进程被加入模块作用域（LSPosed 里勾选）。
 * 用户在 LSPosed 里漏勾时，功能打开后静默不生效、极难排查。本对象把「检测 → 提示 → 补全」
 * 收敛成一处，供功能开关在打开前调用。
 *
 * 依赖的框架能力（libxposed service API 101+）：
 *  - [XposedService.getScope]      —— 查询当前已为模块勾选的作用域
 *  - [XposedService.requestScope]  —— 请求框架新增作用域（框架会向用户弹授权确认）
 *
 * 新增 hook 功能时的用法（页面 DSL）：
 * ```
 * switch(
 *     key = "xxx_enable",
 *     title = "某功能",
 *     requiredScopes = arrayOf("com.android.systemui"),   // 声明该功能需要的作用域
 * )
 * ```
 * 开关打开前会自动检查：缺失则弹窗，用户点「确定」后自动向框架提交勾选请求并继续开启开关；
 * 点「取消」则什么都不做（开关保持关闭）。
 */
object ScopeGuard {

    /** 已绑定的 libxposed service；模块未激活 / 未连接时为 null */
    val service: XposedService?
        get() = XposedServiceBridge.xposedService

    /** 框架连接是否可用 */
    val isAvailable: Boolean
        get() = service != null

    /** 框架中当前已为模块勾选的作用域；查询失败时回落空列表（等价「全部缺失」） */
    fun current(): List<String> =
        runCatching { service?.scope.orEmpty() }.getOrDefault(emptyList())

    /**
     * 从 [required] 中找出尚未勾选的作用域（保持声明顺序并去重）。
     * 返回空列表表示全部已勾选、可直接放行。
     */
    fun missing(required: Array<String>): List<String> {
        val granted = current()
        return required.filter { it.isNotBlank() && it !in granted }.distinct()
    }

    /**
     * 请求框架把 [packages] 加入模块作用域。
     *
     * 框架（LSPosed）会向用户展示授权确认；用户同意后回调 [onResult] 的 ok 为 true。
     * 注意：新增作用域通常要等对应应用进程重启（或框架热重载）后才真正注入模块。
     *
     * @param onResult (ok, errorMessage) —— 回调可能运行在 Binder 线程，调用方需自行切主线程
     */
    fun request(packages: List<String>, onResult: (Boolean, String?) -> Unit) {
        val svc = service
        if (svc == null) {
            onResult(false, "Not connected to Xposed framework")
            return
        }
        runCatching {
            svc.requestScope(
                packages,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        onResult(true, null)
                    }

                    override fun onScopeRequestFailed(message: String) {
                        onResult(false, message)
                    }
                },
            )
        }.onFailure {
            onResult(false, it.message ?: it.toString())
        }
    }

    /**
     * libxposed 的两个特殊作用域包名：它们不是已安装应用，走 PackageManager 取不到标签，
     * 必须用固定展示名（与 LSPosed 作用域界面一致），否则弹窗里只会显示一个裸包名。
     */
    private val SPECIAL_LABELS = mapOf(
        "system" to "系统框架（system_server）",
        "android" to "Android 系统（系统弹窗进程）",
    )

    /** 包名 → 展示名（应用标签）；特殊作用域用固定名，取不到时回落包名本身 */
    fun label(context: Context, packageName: String): String {
        SPECIAL_LABELS[packageName]?.let { return it }
        return runCatching { AppUtils(context).getAppLabel(packageName).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: packageName
    }
}
