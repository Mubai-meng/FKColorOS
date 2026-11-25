# 图标提取工具（material-icons-extended → 手写 ImageVector）

用途：当需要**新增**一个原先由 `material-icons-extended` 提供的图标时，
用这套脚本从官方 AAR 的字节码里**机械还原**路径数据，生成与官方库完全一致的
`ImageVector` 源码，而不是靠手抄 SVG。

工程已于 v1.5.17 移除 `material-icons-extended`（约 19 MB dex 字节码），
改用 `material-icons-core`（14 个常用图标）+ `app/src/main/java/com/fr/colorostools/ui/icons/`
下 43 个手写图标。**新增图标请优先确认 core 是否已自带**，再走本流程。

## 步骤

1. **临时把依赖加回来**（只为拿 AAR，不改工程配置就别提交）
   在 `gradle/libs.versions.toml` + `app/build.gradle.kts` 临时加：
   ```kotlin
   implementation("androidx.compose.material:material-icons-extended:1.7.8")
   ```
   同步一次后，AAR 落在 Gradle 缓存：
   `$GRADLE_USER_HOME/caches/modules-2/files-2.1/androidx.compose.material/material-icons-extended-android/<ver>/<hash>/*.aar`

2. **解出 classes.jar**
   ```bash
   mkdir -p ext_aar && cd ext_aar && unzip -o <上面那个 .aar> classes.jar
   ```

3. **准备 ASM**（`asm` + `asm-tree`，9.x 均可）
   ```bash
   # 从 https://repo1.maven.org/maven2/org/ow2/asm/asm/9.11.1/asm-9.11.1.jar 等下载
   # 同目录放 asm.jar / asm-tree.jar
   ```

4. **挑出需要的图标**（重要：`Extract` 会扫整个目录，别直接喂全部 11000 个类）

   `Extract` 遍历 `args[0]` 下所有 `.class`，**保留目录结构**，包名从目录路径反推。
   所以先建一个 `pick/` 目录，只把要的图标类按原包路径拷进去：

   ```bash
   mkdir -p pick/androidx/compose/material/icons/rounded
   cp ext_cls/androidx/compose/material/icons/rounded/AdbKt.class pick/androidx/compose/material/icons/rounded/
   cp ext_cls/androidx/compose/material/icons/rounded/MemoryKt.class pick/androidx/compose/material/icons/rounded/
   # ... 其余图标同理；automirrored 的要拷到 .../icons/automirrored/rounded/
   ```

5. **跑提取器**（输出 `paths.txt`）

   ```bash
   javac -cp "asm.jar;asm-tree.jar" -d out Extract.java
   java -cp "asm.jar;asm-tree.jar;out" Extract pick > paths.txt
   ```

   输出格式（每个图标一段）：

   ```
   ### MenuOpen|automirrored.rounded|1|3|24|24|24|24
   SEG 0
   PATH moveTo(4.0f, 18.0f)
   PATH lineTo(11.0f, 18.0f)
   ...
   ```

   字段依次为：`图标名 | 包后缀 | autoMirror | 分段数 | defaultW | defaultH | viewportW | viewportH`。
   图标名取自 `XxxKt.class`（`AdbKt` → `Adb`）。

6. **生成 Kotlin 源码**

   ```bash
   cd tools/icon-extract && python gen2.py paths.txt
   ```

   - 输出目录由脚本自己算（`../../app/src/main/java/com/fr/colorostools/ui/icons`），**不用传**；
   - `CORE` 集合排除 material-icons-core 已自带的图标（避免重复定义）；
   - 自动把 `arcToRelative` / `arcTo` 的布尔位参从 `1.0f/0.0f` 转成 `true/false`；
   - 同时生成 `import_map.json`（旧 import → 新 import 的映射表）。

7. **改 import**：按 `import_map.json` 批量替换业务代码里的
   `androidx.compose.material.icons.rounded.Xxx` → `com.fr.colorostools.ui.icons.rounded.Xxx`。

## 已验证的坑

- `ImageVector` 在 **`androidx.compose.ui:ui` 的 `ui.aar`** 里，不在 `ui-graphics.aar`（后者只有 `PathBuilder`）。
- 路径数据来自 `ImageVector.Builder.<init>` + `PathBuilder` 的调用序列，坐标是 `ldc` 浮点常量。
- 多段 path 靠 `getNodes()` 的返回值切分，不能按命令类型猜。
- `Dp` 维度在字节码里是 `Dp."constructor-impl"(F)F`，直接取那个 float。
- 生成后务必做一次**双向比对**（从 `paths.txt` 解析 vs 从生成源码解析），本次 43 个图标 0 mismatch。
