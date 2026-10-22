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

/** 本次构建的版本号（读取并自增 version.properties） */
val appVersion: AppVersionInfo = readAndBumpVersion()

android {
    namespace = gropify.project.groupName
    compileSdk = gropify.project.android.compileSdk

    defaultConfig {
        applicationId = gropify.project.groupName

        minSdk = gropify.project.android.minSdk
        targetSdk = gropify.project.android.targetSdk

        // 每次构建自动抬升：versionCode +1、versionName 补丁号 +1
        versionCode = appVersion.code
        versionName = appVersion.name
        ndk.abiFilters.addAll(arrayOf("arm64-v8a"/*, "armeabi-v7a", "x86", "x86_64"*/))
    }
    signingConfigs {
        all {
            // 2026-10-19：改 V2-only 签名（用户拍板）。
            // 背景：V1 依赖 SHA-1，本机 JDK 安全配置禁用 SHA-1（jarsigner 实证），签不出；
            // V2+V3 同时启用时本机 apksigner（33~37 全版本）+ AGP 9.4.1 产物 verify 均报
            // V2=false（V2 block 实际已写入，是 verify 在 V3 并存时的判定行为），无法同时
            // 交付两者。本模块 minSdk=30（Android 11+），V2 已覆盖全部目标设备。
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = false
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
        // apksigner 三版本交叉验证一致）。当前策略 = 仅 V2（详见 signingConfigs 注释）。
        variant.signingConfig?.let { sc ->
            sc.enableV1Signing.set(false)
            sc.enableV2Signing.set(true)
            sc.enableV3Signing.set(false)
        }
        val buildType = variant.buildType ?: "None"
        variant.outputs.forEach { output ->
            val versionName = output.versionName.get()
            val versionCode = output.versionCode.get()
            val version = "$versionName($versionCode)"
            println("buildVersion -> $version ($buildType)")

            output.outputFileName.set("FKColorOS_v${version}_${buildType}.apk")
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
    implementation(libs.androidx.compose.material.icons.extended)
    //Miuix
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    //Markdown
    implementation(libs.markwon.core)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.ext.tables)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    //Android UI
    implementation(libs.betterandroid.ui.component)
    implementation(libs.betterandroid.ui.component.adapter)
    implementation(libs.betterandroid.ui.extension)
    implementation(libs.betterandroid.system.extension)

    //KotlinX
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)

    //OkHttp3
    implementation(libs.okhttp3)
    implementation(libs.net)

    //LibSU
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.io)

    //Tools
    implementation(libs.deviceCompat)
    implementation(libs.xxpermissions)
    implementation(libs.spiderman)
    implementation(libs.android.image.cropper)

    //Test
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

/** 构建期版本信息（读取并写回 app/version.properties） */
data class AppVersionInfo(val name: String, val code: Int)

/**
 * 补丁号自增（满 100 进位次版本号、次版本号归零）：1.0.58 -> 1.0.59、1.0.99 -> 1.1.0
 * （2026-10-19 加进位：纯 patch+1 永远产不出 x.y.0，发小版本时只能改构建逻辑；
 *  忽略 -/_ 后缀）
 */
fun bumpPatchVersion(name: String): String {
    val core = name.substringBefore('-').substringBefore('_')
    val parts = core.split('.').mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 1 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 } + 1
    return if (patch >= 100) "$major.${minor + 1}.0" else "$major.$minor.$patch"
}

fun readAndBumpVersion(): AppVersionInfo {
    val propsFile = file("version.properties")
    if (!propsFile.canRead()) throw GradleException("Can't read version.properties!")
    val properties = Properties()
    properties.load(FileInputStream(propsFile))
    val nextCode = properties["versionCode"].toString().toInt() + 1
    val currentName = properties.getProperty("versionName", "1.0.0")
    val nextName = bumpPatchVersion(currentName)
    properties["versionCode"] = nextCode.toString()
    properties["versionName"] = nextName
    properties.store(propsFile.writer(), null)
    println("buildVersion -> $nextName($nextCode)")
    return AppVersionInfo(nextName, nextCode)
}
