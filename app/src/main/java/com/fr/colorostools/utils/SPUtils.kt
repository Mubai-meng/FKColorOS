@file:Suppress("DEPRECATION", "WorldReadableFiles", "ApplySharedPref", "UseKtx", "unused")

package com.fr.colorostools.utils

import android.content.Context
import android.util.ArrayMap
import android.util.ArraySet
import androidx.collection.arrayMapOf
import androidx.collection.arraySetOf
import com.fr.colorostools.ui.service.XposedServiceBridge

const val ModulePrefs: String = "ModulePrefs"
const val IntentPrefs: String = "IntentPrefs"
const val SettingsPrefs: String = "SettingsPrefs"
const val OtherPrefs: String = "OtherPrefs"

/**
 * UI 侧 prefs 访问统一入口：绑定 libxposed service 时走 remote prefs
 * （与宿主进程 YukiHookPreferences 同一数据源），未绑定时回落本地 prefs。
 */
internal fun Context.appPrefs(prefsName: String): android.content.SharedPreferences =
    XposedServiceBridge.preferences(prefsName)
        ?: getSharedPreferences(prefsName, Context.MODE_PRIVATE)


fun Context.getString(prefsName: String, key: String, defaultValue: String = ""): String {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.getString(key, defaultValue) ?: defaultValue
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "getString $key -> $defaultValue", "$t", true)
        defaultValue
    }
}

fun Context.putString(prefsName: String, key: String, value: String): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().putString(key, value).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "putString $key -> $value", "$t", true)
        false
    }
}

fun Context.getStringSet(
    prefsName: String, key: String, defaultValue: Set<String> = arraySetOf()
): Set<String> {
    return try {
        val prefs = appPrefs(prefsName)
        ArraySet(prefs.getStringSet(key, defaultValue))
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "getStringSet $key -> $defaultValue", "$t", true)
        defaultValue
    }
}

/**
 * 跨进程传输安全的集合副本。
 *
 * remote prefs（libxposed RemotePreferences）是把写入值打包进 Bundle 经 Binder 交给框架进程执行的，
 * 接收侧按 Serializable 反序列化，而 Parcel 的默认类解析走 bootstrap 类加载器：Kotlin 单例集合
 * （`emptySet()` 得到的 `kotlin.collections.EmptySet`）与 androidx 集合都不在 boot 路径上，解析不到就抛
 * `android.os.BadParcelableException: ... ClassNotFoundException ... EmptySet`，这一次 prefs 更新连同
 * 同批的其它键一起失败（表现为设置写不进宿主进程）。写入前统一复制成 java.util.HashSet：
 * 值类型与语义都不变，也不再把 Kotlin 实现泄漏给框架。
 */
internal fun Set<String>.toPrefsSafeSet(): HashSet<String> = HashSet(this)

fun Context.putStringSet(prefsName: String, key: String, value: Set<String>): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().putStringSet(key, value.toPrefsSafeSet()).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "putStringSet $key -> $value", "$t", true)
        false
    }
}

fun Context.getInt(prefsName: String, key: String, defaultValue: Int): Int {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.getInt(key, defaultValue)
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "getInt $key -> $defaultValue", "$t", true)
        defaultValue
    }
}

fun Context.putInt(prefsName: String, key: String, value: Int): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().putInt(key, value).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "putInt $key -> $value", "$t", true)
        false
    }
}

fun Context.getLong(prefsName: String, key: String, defaultValue: Long): Long {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.getLong(key, defaultValue)
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "getLong $key -> $defaultValue", "$t", true)
        defaultValue
    }
}

fun Context.putLong(prefsName: String, key: String, value: Long): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().putLong(key, value).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "putLong $key -> $value", "$t", true)
        false
    }
}

fun Context.getFloat(prefsName: String, key: String, defaultValue: Float): Float {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.getFloat(key, defaultValue)
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "getFloat $key -> $defaultValue", "$t", true)
        defaultValue
    }
}

fun Context.putFloat(prefsName: String, key: String, value: Float): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().putFloat(key, value).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "putFloat $key -> $value", "$t", true)
        false
    }
}

fun Context.getBoolean(prefsName: String, key: String, defaultValue: Boolean = false): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.getBoolean(key, defaultValue)
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "getBoolean $key -> $defaultValue", "$t", true)
        defaultValue
    }
}

fun Context.putBoolean(prefsName: String, key: String, value: Boolean): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().putBoolean(key, value).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "putBoolean $key -> $value", "$t", true)
        false
    }
}

/**
 * 删除键值数据
 * @receiver Context
 * @param prefsName String
 * @param key String
 */
fun Context.removeKey(prefsName: String, key: String): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().remove(key).commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "removeKey $key", "$t", true)
        false
    }
}

/**
 * 删除此配置键值数据
 * @receiver Context
 * @param prefsName String?
 * @return Boolean
 */
fun Context.clearPrefs(prefsName: String): Boolean {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.edit().clear().commit()
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "clearPrefs $prefsName", "$t", true)
        false
    }
}

/**
 * 删除全部配置键值数据
 * @receiver Context
 * @param prefList Array<out String?>
 */
fun Context.clearAllPrefs(vararg prefList: String): Boolean {
    val curStatus = BooleanArray(prefList.size)
    prefList.forEachIndexed { index, name ->
        try {
            val prefs = appPrefs(name)
            curStatus[index] = prefs.edit().clear().commit()
        } catch (t: Throwable) {
            LogUtils.e("SPUtils", "clearAllPrefs $name", "$t", true)
            curStatus[index] = false
        }
    }
    return curStatus.contains(false)
}

/**
 * 获取配置键值数据
 * @receiver Context
 * @param prefsName String?
 * @return MutableMap<String, *>?
 */
fun Context.backupPrefs(prefsName: String): MutableMap<String, *> {
    return try {
        val prefs = appPrefs(prefsName)
        prefs.all
    } catch (t: Throwable) {
        LogUtils.e("SPUtils", "backupPrefs $prefsName", "$t", true)
        arrayMapOf<String, Any>()
    }
}

/**
 * 获取配置键值数据
 * @receiver Context
 * @param prefList Array<out String?>
 * @return ArrayMap<String, MutableMap<String, *>>?
 */
fun Context.backupAllPrefs(vararg prefList: String): ArrayMap<String, MutableMap<String, *>?> {
    val map = ArrayMap<String, MutableMap<String, *>?>()
    prefList.forEachIndexed { _, name ->
        try {
            val prefs = appPrefs(name)
            map[name] = prefs.all
        } catch (t: Throwable) {
            LogUtils.e("SPUtils", "backupAllPrefs $name", "$t", true)
            map[name] = arrayMapOf<String, Any>()
        }
    }
    return map
}