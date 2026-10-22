# 天气 App「不外跳浏览器」Hook 方案分析

> 宿主：`com.coloros.weather2`（系统桌面天气 17.7.4，逆向素材 `E:/WorkBuddy_All/APP/oneplus/decompiled/weather`）
> 目标：主页点击各类信息（今日/未来天气详情、生活指数、空气质量、天气标签、公告、运营卡片、热点新闻…）时，
> **不跳系统/HEYTap 浏览器**，改用应用自带 WebView（`WeatherWebActivity`）展示。
> 结论先行：**推荐方案 A（startActivity 级拦截，一网打尽）**；热点新闻的浏览器深链需单独评估（见 §5）。

## 1. 摘要

| 项 | 结论 |
|---|---|
| 应用自带应用内 WebView？ | **有**。`com.oplus.weather.plugin.webview.WeatherWebActivity`（`BaseWebActivity` 子类，带 H5 JS 桥、日夜模式、实例数限制） |
| 官方应用内入口 | `BrowserCommonUtils.startWeatherWebActivity(ctx, url, isFirst, statisticsTag, isSingleTop)` —— 拼 Intent（extra：`intent_params_url`/`intent_params_isFirst`/`intent_params_statistics`）+ 300ms 防抖 + 无缝动画 |
| 为什么现在跳浏览器 | 正式版（非体验版）所有二级页跳转**默认走外跳浏览器**；应用内 WebView 只是「浏览器不可用」时的兜底（`SecondaryPageUtil.gotoWebView` / `LocalUtils` 的 exp 分支） |
| 跳转家族 | 4 个（见 §2），最终都收敛到 `context.startActivity(Intent)` / `startActivityForResult(Intent, int)` |
| 推荐钩子 | `android.app.Activity#startActivity` + `#startActivityForResult(Intent,int)`（+ `ContextWrapper#startActivity` 兜底非 Activity context），按 intent 形状过滤后**替换参数**为 WeatherWebActivity intent |
| 性能影响 | 可忽略（点击驱动的低频路径，钩子体 = 2 次字符串比较 + 1 次 scheme 判断）；不外跳反而少唤醒一次浏览器进程 |
| 唯一硬骨头 | 热点新闻是 `heytapbrowser://` 自定义 scheme 深链，WebView 加载不了，需先提取真实 http URL（缺真实样本，建议先保留外跳） |

## 2. 逆向实证：四类跳转家族

### 2.1 家族一：`SecondaryPageUtil.startJumpToBrowser`（天气标签/公告/生活指数运营卡）

`com/oplus/weather/utils/SecondaryPageUtil.java`（类名未混淆）：

| 重载 | 行为 |
|---|---|
| 3 参 `(ctx, url, isWeatherInnerJump)` :206 | Android P+ ∧ 浏览器不支持跳转 ⇒ **应用内**（:216 `startWeatherWebActivity`）；否则转 11 参重载 |
| 11 参 :270 | 起 IO 协程 → `obtainJumpToBrowserIntent(...)` 拼 intent（遍历 `BROWSER_PACKAGES = {com.heytap.browser, com.android.browser, com.coloros.browser}` 找已安装的，`setPackage`，ACTION_VIEW + http data）→ 主线程 `context.startActivity(intent, seamlessBundle)`；失败 ⇒ `intent.setPackage(null)` 重试 ⇒ 再失败 ⇒ `gotoWebView()` 兜底应用内 |

调用方（主页）：`main/view/itemview/WeatherTagItem` :168/:199、`NoticeItem` :1588/:1624、
`indexoperations/IndexOperationsManager.operationJump` :326（生活指数/运营卡片总入口）。

### 2.2 家族二：`LocalUtils.jumpToBrowser`（今日/未来天气详情、生活指数子项、气象详情、广告）

`com/oplus/weather/utils/LocalUtils.java`：

```
startBrowserForAd/startBrowserForUrl（多个重载）
  └─ jumpToBrowser(ctx, int, Pair(url, backup), tag, ...)      :615
       ├─ AppFeature.isExpVersion（体验版）⇒ startWeatherWebActivity 应用内 ✓
       └─ 正式版 ⇒ launchHeyTapBrowserFirst(...)               :888
            ├─ intent.action = "com.heytap.browser.action.DETAIL_PAGE"（自定义 action！）
            ├─ intent.setPackage(浏览器包名) + data = http URL
            └─ startActivityForResult(intent, 4) / startActivity(intent)
                 └─ ActivityNotFoundException ⇒ setPackage(null) 重试 ⇒ gotoWebView 兜底
```

另有两个专用入口同样收敛到 `jumpToBrowser`：
- `jumpToWeatherMeteorology(ctx, url, tag)` :623（生活指数子项「气象详情」，`LifeGroupChildItem` :334 调）
- `jumpToCctvWeatherForecastBrowser` :637（央视天气预报）

调用方（主页）：`TodayWeatherItem` :591/:597、`FutureDayWeatherItem` :1932、`LifeGroupChildItem` :320/:324/:352、
`viewmodel/AirDetailPanelVM`、`ad/AdUtils`。

### 2.3 家族三：`HotNewsJumpHelper.jump`（主页热点新闻卡片）

`main/hotnews/HotNewsJumpHelper.java`：URL 必须是 **light-stack 深链**
（`heytapbrowser://com.heytap.browser.light.stack`，scheme/host 常量 :27-28），
`buildLightStackIntent` :184 拼 `ACTION_VIEW + setPackage(浏览器) + data=heytapbrowser://…`。
`resolveJumpUrl` 只认 `Jump.jumpTo`/`backupJumpTos`（服务端下发），**没有独立 http URL 字段**。

### 2.4 家族四：`IndexOperationsManager.jumpActivity`（deepLink/installAppLink）

:339 直接 `new Intent(ACTION_VIEW) + setData(Uri.parse(url))`（不 setPackage）。
deepLink 可能是 http（会进浏览器）也可能是其他 App 的自定义 scheme（应放行）。

### 2.5 应用内 WebView 自身的外跳（次要）

`WeatherWebActivity$mWebViewClient$1.shouldOverrideUrlLoading` :118：http/https 走 WebView 内加载；
其他 host 落入 `super` ⇒ 系统处理（可能外跳）。以及 `WeatherWebActivity.startActivity(url)` 私有方法
对非 http scheme 转外部 intent。**属于 H5 页内链接，优先级低于主页四类**，本方案默认不拦（见 §6 风险）。

## 3. 推荐方案：A —— startActivity 级拦截

### 3.1 钩子点（全部框架方法，与应用混淆无关）

| 方法 | 覆盖 |
|---|---|
| `android.app.Activity#startActivity(Intent)` | 绝大多数（Activity context） |
| `android.app.Activity#startActivityForResult(Intent, int)` | `launchHeyTapBrowserFirst` :906、`HotNewsJumpHelper` :126 |
| `android.content.ContextWrapper#startActivity(Intent)` | 兜底（Application context 路径，极少） |

只在 `com.coloros.weather2` 进程注入（模块作用域），不影响其他 App。

### 3.2 过滤器（命中才改写，否则原样放行）

```
action ∈ { "android.intent.action.VIEW", "com.heytap.browser.action.DETAIL_PAGE" }
∧ data.scheme ∈ { "http", "https" }
∧ ( package ∈ {com.heytap.browser, com.android.browser, com.coloros.browser}
    ∨ (package == null ∧ component == null) )     // 无形参、无显式组件 = 纯网页
```

- 形如 `market://`、`myapp://` 的其他 App 深链：scheme 不匹配 ⇒ 放行 ✓
- App 内自跳（显式 component）：`component != null` ⇒ 放行 ✓
- `heytapbrowser://` light stack：scheme 不匹配 ⇒ 放行（热点新闻保持原生，见 §5）✓

### 3.3 改写动作

1. 取 `intent.data.toString()`（此时 URL 已带齐应用拼好的参数：`from_app_location`、城市参数、`fromWeatherApp` 等）
2. 反射构造替换 intent：
   `new Intent(ctx, WeatherWebActivity.class)`（类从宿主 ClassLoader 加载），
   extra：`intent_params_url`=url、`intent_params_isFirst`=true、`intent_params_statistics`=""（或原 tag）
3. **替换入参后 `chain.proceed(newIntent)`** —— 走完正常 Activity 启动流程，无缝动画/返回栈全部保持原生

> 备选：反射调 `BrowserCommonUtils.startWeatherWebActivity(ctx, url, true, "", true)` 后不 proceed。
> 不推荐作主路径：它是 static + 300ms 防抖（防抖态下点击被吞），且绕过 proceed 的异常语义更差。

### 3.4 为什么不是「钩应用工具类」（方案 B）

| 维度 | A（startActivity 拦截） | B（钩 SecondaryPageUtil/LocalUtils） |
|---|---|---|
| 钩子数 | 3 个框架方法 | ≥5 个应用方法（11 参/3 参/Pair/String 重载 + startBrowserForAd + jumpToWeatherMeteorology + jumpToCctv…） |
| 覆盖面 | 四类家庭 + 未来新增路径自动覆盖 | 漏一个入口漏一片 |
| 混淆鲁棒性 | 框架方法永久稳定 | 应用类名当前未混淆，但升级可能变 |
| URL 参数 | 拿到最终 URL（应用已拼好） | 需自己复刻 getH5StringBuffer/城市参数拼接 |
| 语义精确性 | 稍粗（靠过滤器） | 精确 |

B 可作为 A 的补充（如某条路径的 intent 形状不在过滤器内），但不应作主路径。

## 4. 性能评估（用户关注点）

| 项 | 评估 |
|---|---|
| 钩子体开销 | 每次 startActivity：2 次 String 比较 + 1 次 Uri.getScheme + 1 次 Set 查找。天气 App 的 startActivity 全部由用户点击驱动（秒级一次），**可忽略** |
| 加载开销 | 与原生路径同为一次 Activity 启动；WebView 初始化在 WeatherWebActivity 内部，与应用自己兜底路径完全一致 |
| 对比外跳 | 不外跳 = 不唤醒浏览器进程（通常还带一次进程冷启动），**更省** |
| 后台 | 无常驻轮询、无额外线程 |

## 5. 热点新闻（light stack）单独评估

`heytapbrowser://com.heytap.browser.light.stack?...` 是 HEYTap 浏览器私有深链，WebView 无法加载。
三条路：

| 选项 | 做法 | 评价 |
|---|---|---|
| B1 提取真实 URL | 解析 light-stack URI 的 query，找 http(s) 参数（常见 `url`/`link`/`target`）→ 走应用内 | 最优，但**需要一个真实样本**确认参数名（可抓包/借浏览器日志）。缺样本前不做 |
| B2 保留外跳 | 过滤器天然放行 `heytapbrowser://` | **推荐默认**：热点新闻继续跳浏览器，其余全部应用内 |
| B3 复刻 light stack | 在应用内实现 DP 容器 | 过度设计，否 |

⇒ 实现时把「热点新闻」做成**独立开关**（默认关），拿到真实样本后再开 B1。

## 6. 风险与降级

| 风险 | 降级 |
|---|---|
| 某 H5 页面在应用内 WebView 表现异常（白屏/功能缺失） | 加域名黑名单开关（默认空）；或整体开关一键回到原生外跳 |
| 应用升级后 `WeatherWebActivity` 改名/被删 | 安装期探测类存在性，不存在则该功能不装（记 W），其余 hook 功能不受影响 |
| extra 键名变更 | 同上，探测 `INTENT_PARAMS_URL` 常量（反射读 `WeatherWebActivity.INTENT_PARAMS_URL`，读不到用硬编码 "intent_params_url" 兜底） |
| H5 页内链接外跳（§2.5） | 本期不拦；如需要，同方案加 `shouldOverrideUrlLoading` 钩子（第二期） |
| 统计埋点丢失（浏览器侧埋点） | 可接受（详情页 PV 走应用内统计）；开关关闭即恢复 |
| 误拦其他 App 深链 | 过滤器只放行 http/https + 无显式组件，`market://` 等一律放行 |

## 7. 落地要素（确认后实现）

| 文件/项 | 内容 |
|---|---|
| `hook/hookers/WeatherNoBrowserHook.kt` | 新 hooker：3 个 startActivity 钩子 + 过滤器 + intent 改写（全部独立 runCatching，失败原样放行） |
| `hook/HookEnv.kt` | 加 `PKG_WEATHER = "com.coloros.weather2"` |
| `hook/MainHook.kt` | `onPackageLoaded` 加天气分发分支（进程级 AtomicBoolean 防重装） |
| `META-INF/xposed/scope.list` | 加 `com.coloros.weather2` |
| `ui/compose/scopes/apps/WeatherNoBrowserPage.kt` + `ScopePageRegistry` | 二级页：总开关（+ 预留热点新闻开关，默认关） |
| `LauncherMenuConfig` 同款 remote prefs | `weather_no_browser_enabled` 等键 |
| strings（中英） | 页面文案 |

预期日志（验证用）：
`WeatherNoBrowser: redirect -> url=… from=ACTION_VIEW pkg=com.heytap.browser`（每次改写一条 I）。

## 8. 待用户确认

1. 采用方案 A（startActivity 级拦截）+ 热点新闻暂保留外跳（B2）？
2. 是否需要抓一个热点新闻真实跳转 URL 样本（决定 B1 能否做）？如需要，提供一条热点新闻点击后的 LSPosed 日志或浏览器地址栏截图即可。
