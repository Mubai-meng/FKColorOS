# FKColorOS

面向 ColorOS 17 的 LSPosed 模块，把系统里那些「本该可配却不能配」的行为改成可配。

基于 **libxposed API 102**（现代模块，非 XposedBridge 兼容模式），Kotlin + Jetpack Compose 编写。

> 当前版本 **1.1.0** · 仅适配 arm64-v8a · 需要 Android 11+（minSdk 30）· 只在 ColorOS 17 / OnePlus 15 上做过真机验证

---

## 适配环境

| 项 | 值 |
|---|---|
| 系统 | ColorOS 17 |
| 设备 | OnePlus 15（真机验证机） |
| Android | API 37（compileSdk / targetSdk 37） |
| 框架 | LSPosed（libxposed API 102） |
| ABI | arm64-v8a |
| 构建 | AGP 9.4.1 · Kotlin 2.4.21 · JDK 21 · Compose BOM 2026.09.00 |

模块作用域固定为四个宿主，安装后 LSPosed 里会自动预勾选：

```
system                     系统框架（system_server）
com.oplus.metis            流体云
com.android.settings       设置
com.android.launcher       系统桌面
com.coloros.weather2       天气
```

> `system` 是 libxposed 里 system_server 的作用域名，LSPosed 界面显示为「系统框架」。
> 别和 `android` 搞混——后者是 ChooserActivity 这类系统弹窗进程，勾了不会触发 system_server 侧逻辑。

---

## 功能

| 功能 | 作用 | 宿主 |
|---|---|---|
| 流体云替换 / 自定义 | 改写流体云对文本 Intent 的识别结果，替换成自定义目标或自定义内容 | `com.oplus.metis` |
| 无障碍增强 | 绕过设置里对无障碍服务开关的限制，直接写 Secure 表放开指定服务 | `com.android.settings` |
| 桌面长按菜单 | 定制 App 图标长按弹出的菜单项：增删条目、改写深链段与更多菜单 | `com.android.launcher` |
| 天气不外跳浏览器 | 天气 App 里的二级页改为应用内 WebView 打开，不再跳到浏览器 | `com.coloros.weather2` |

每个开关独立，关闭即完全不动宿主行为。宿主侧只做「类存在才安装 hook」的自适应判断，所以系统小版本更新导致某个类消失时，对应 hook 会自己跳过，不会连累其他功能。

### 实现取向

所有 hook 都在目标应用的进程内完成，不常驻后台、不起服务、不联网。反射调用统一走 `Reflect`，遇到方法声明在父类的情况用 `findMethodDeep` / `callDeep`——这一点上踩过静默失效的坑，相关约定写在代码注释里。

---

## 安装

1. 设备已 root 并装好 LSPosed
2. 安装 `FKColorOS_v{version}({code})_debug.apk`
3. LSPosed 里启用模块，确认作用域已勾选上表四个宿主 + 系统框架
4. 重启作用域内应用（改设置类功能建议直接重启设备）

装好后打开 App，底部五个页签：首页 / 功能 / 日志 / 设置 / 其他。功能树里每个节点对应上面一个宿主应用，点进去是各自开关。

---

## 构建

```bash
git clone https://github.com/Mubai-meng/FKColorOS.git
cd FKColorOS
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/FKColorOS_v{versionName}({versionCode})_debug.apk`

构建前需要自备两样东西（都不在仓库里）：

- **`local.properties`** — 指向本机 SDK：`sdk.dir=/path/to/android-sdk`
- **`keystore/keystore.properties`** — 签名信息，四个键：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`；对应的 `.jks` 放同目录

版本号由构建脚本自动抬升：每次构建 `versionCode +1`、`versionName` 补丁号 +1（满 100 进位 minor），状态写在 `app/version.properties`。

签名方案为 **V2-only**（V1/V3/V4 关闭）。原因：V1 依赖 SHA-1，本机 JDK 安全策略已禁；V2 与 V3 同时启用时 `apksigner verify` 在多版本上都报 V2=false（block 实际写入，属判定行为），无法同时干净交付。minSdk 30 意味着 V2 已覆盖全部目标设备。

---

## 日志

常规日志异步写入（守护线程 + 有界队列），ERROR 级同步落盘，保证崩溃前不丢关键行。

ColorOS 17 的分区存储下 `File` API 写 `/sdcard/Download` 必然失败（uid=1000 也不行），所以日志走 **MediaStore**：按当天条目复用、`IS_PENDING` 翻转保证跨进程可见，自动清理 7 天前文件。App 的日志页可直接看，也可分享。

---

## 项目结构

```
app/src/main/java/com/fr/colorostools/
├── hook/
│   ├── MainHook.kt          分发入口（onSystemServerStarting / onPackageLoaded）
│   ├── HookEnv.kt           进程环境、context 绑定、设置读取
│   └── hookers/             各功能 Hooker，一个 object 一个功能
├── ref/Reflect.kt           反射工具
├── utils/LogUtils.kt        日志三通道
└── ui/compose/              Compose UI，页签 + 功能树 + 作用域门禁
hidden-api-stub/             隐藏 API 编译桩
docs/                        各功能的逆向分析与方案评估
```

新增功能的标准落位：`hookers/HookXxx.kt` 写 hook → `MainHook` 加分发 → App 设置项写 remote prefs → `scopes/apps/HookXxxPage.kt` 做页面 → `ScopePageRegistry` 注册 → `scope.list` 补宿主。细节见 `docs/` 下的方案文档。

---

## 说明

- 本模块修改的是系统与系统应用行为，**刷机 / 系统升级后可能失效**，不保证跨版本可用
- 不同机型、不同 ROM 分支上的类名与方法签名可能不同，只保证上表环境
- 仅供个人设备定制使用。因使用本模块造成的设备异常、数据丢失，自行承担
- 仓库不含签名密钥，`keystore/`、`local.properties`、构建产物均已忽略

## License

未附带开源许可证。源码公开供参考，保留所有权利。
