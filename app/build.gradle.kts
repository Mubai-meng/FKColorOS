import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.lsplugin.lsparanoid)
    alias(libs.plugins.kotlin.serialization)
}

lsparanoid {
    includeDependencies = false
    variantFilter = { variant -> variant.name == "release" }
}

/**
 * 本次构建的版本号（只读 version.properties，**不再自增**）。
 * 2026-11-24：按用户要求去掉构建期自增 —— 每次构建都改版本号会让
 * 增量构建缓存失效、产物名跳号、且无法重出一模一样的包。
 * 需要抬版本时手工改 app/version.properties 的两个字段即可。
 */
val appVersion: AppVersionInfo = readVersion()

android {
    namespace = gropify.project.groupName
    compileSdk = gropify.project.android.compileSdk

    defaultConfig {
        applicationId = gropify.project.groupName

        minSdk = gropify.project.android.minSdk
        targetSdk = gropify.project.android.targetSdk

        // 版本号来自 app/version.properties（只读，不再构建期自增）
        versionCode = appVersion.code
        versionName = appVersion.name
        ndk.abiFilters.addAll(arrayOf("arm64-v8a"/*, "armeabi-v7a", "x86", "x86_64"*/))
    }
    signingConfigs {
        all {
            // 2026-11-22：改 V2+V3 双方案签名（用户要求）。
            // V1 依赖 SHA-1，本机 JDK 安全配置禁用 SHA-1（jarsigner 实证），签不出 ⇒ 关闭。
            // 关于 verify 显示 v2=false 的实证结论（2026-11-22 复核）：
            //   1) 直接解析 APK Signing Block 可见 0x7109871a(V2) 与 0xf05368c0(V3) 两块
            //      均已真实写入，长度各 1326 字节；
            //   2) apksigner（34/35/36.1/37 全版本）默认按 minSdk=30 只验 V3，故显示 v2=false；
            //      加 --min-sdk-version 24 --max-sdk-version 29 强制低区间后，
            //      v2=true 且 v3=true 同时成立。
            // ⇒ 双方案真实有效，v2=false 仅是 verify 的 SDK 区间判定显示行为，不是签名缺失。
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = null

            storeFile = file(rootProject.extra.get("storeFile") as String)
            storePassword = rootProject.extra.get("storePassword") as String
            keyAlias = rootProject.extra.get("keyAlias") as String
            keyPassword = rootProject.extra.get("keyPassword") as String
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = false
            isMinifyEnabled = false
            //noinspection NotShrinkingResources
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
    androidResources.additionalParameters.addAll(
        arrayOf("--allow-reserved-package-id", "--package-id", "0x64")
    )
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        dex {
            // AGP 默认在 minSdk>=28 时把 dex 以 STORED（不压缩）方式打进 APK，以便安装时
            // 直接 mmap、启动更快。代价是 debug 包（无 R8 压缩）里 5 个 dex 合计 61.6MB
            // 原样占位。实测这些 dex 的 deflate 压缩率约 29%（61.6MB -> 17.8MB）。
            // 置 true 后改走 DEFLATE，代价仅是安装时多一次解压、首次启动略慢，
            // 对 Xposed 模块无感。release 有 R8 兜底，dex 只有 6.5MB，也可同享。
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(gropify.project.jdk.version)
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        // AGP 9：签名方案必须走 variant API —— DSL `signingConfigs { }` 里的
        // enableV1/V2/V3 赋值在新版不再作用于实际签名（2026-10-19 实测只出了 V3，
        // apksigner 三版本交叉验证一致）。当前策略 = V2+V3 双方案（详见 signingConfigs 注释）。
        variant.signingConfig?.let { sc ->
            sc.enableV1Signing.set(false)
            sc.enableV2Signing.set(true)
            sc.enableV3Signing.set(true)
        }
        val buildType = variant.buildType ?: "None"
        variant.outputs.forEach { output ->
            val versionName = output.versionName.get()
            // 产物名只带 versionName，不再拼 (versionCode)：FKColorOS_v1.2.0_release.apk
            println("buildVersion -> $versionName ($buildType)")

            output.outputFileName.set("FKColorOS_v${versionName}_${buildType}.apk")
            println("outputFileName -> ${output.outputFileName.get()}")
        }
    }
}

dependencies {
    compileOnly(projects.hiddenApiStub)

    // libxposed API 102（模块入口 + service 桥接）
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.kavaref.core)
    implementation(libs.kavaref.android)
    implementation(libs.kavaref.extension)

    implementation(libs.dexkit)
    implementation(libs.hiddenapibypass)

    //AndroidX
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    //Compose (BOM 统一版本)
    platform(libs.androidx.compose.bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.kolor)
    // 只保留 icons-core（Home/Lock/Check/Info/Search 等 14 个基础图标），
    // 其余图标已手写在 ui/icons/ 下，省下 material-icons-extended 的 9.7MB 字节码
    implementation(libs.androidx.compose.material.icons.core)
    //Miuix
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    //Markdown（只用 core + ext-tables；html/image 两个插件源码零引用，2026-11-24 移除）
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // 图片加载：全工程零引用（无 AsyncImage / ImageRequest / coil 调用），
    // 之前是靠 coil-compose + coil-network-okhttp 白占 dex，2026-11-24 移除。
    // 若后续要显示网络图片再按需加回。

    //Android UI
    implementation(libs.betterandroid.ui.component)
    implementation(libs.betterandroid.ui.component.adapter)
    implementation(libs.betterandroid.ui.extension)
    implementation(libs.betterandroid.system.extension)

    //KotlinX（serialization 只用 json；protobuf 源码零引用，2026-11-24 移除）
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    //OkHttp3
    implementation(libs.okhttp3)
    implementation(libs.net)

    //LibSU
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.io)

    //Tools
    implementation(libs.xxpermissions)
    // DeviceCompat 由 XXPermissions 传递引入，不需要单独声明
    implementation(libs.android.image.cropper)

    //Test
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

/** 构建期版本信息（来自 app/version.properties，只读） */
data class AppVersionInfo(val name: String, val code: Int)

/**
 * 读取版本号（**只读，不写回**）。
 *
 * 2026-11-24 移除原有的 readAndBumpVersion()：
 * - 原逻辑在 Gradle **配置阶段**就 `properties.store(...)` 写文件，导致
 *   1) 每次 assembleDebug 都产生一次「配置阶段写文件」⇒ 配置缓存永远不可复用；
 *   2) 产物名/versionCode 每次都变 ⇒ UP-TO-DATE 判定永远失效，等于强迫全量重打包；
 *   3) 同一个 commit 重编译得到的 APK 与上次不一致，无法做体积/行为对比。
 * 需要抬版本时手工改 version.properties 的 versionCode / versionName。
 */
fun readVersion(): AppVersionInfo {
    val propsFile = file("version.properties")
    if (!propsFile.canRead()) throw GradleException("Can't read version.properties!")
    val properties = Properties()
    FileInputStream(propsFile).use { properties.load(it) }
    val code = properties["versionCode"].toString().toInt()
    val name = properties.getProperty("versionName", "1.0.0")
    println("buildVersion -> $name($code) [read-only]")
    return AppVersionInfo(name, code)
}
