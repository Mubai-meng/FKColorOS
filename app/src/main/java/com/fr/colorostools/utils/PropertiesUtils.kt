package com.fr.colorostools.utils

import android.text.TextUtils
import java.util.Properties

fun Properties.getStringProperty(key: String): String? {
    return if (TextUtils.isEmpty(key)) {
        null
    } else getProperty(key)
}

fun Properties.getStringProperty(key: String, def: String = ""): String {
    return if (TextUtils.isEmpty(key)) def
    else getStringProperty(key) ?: def
}

/**
 * 读取Int
 * @receiver Properties
 * @param key String
 * @return Int 不存在或异常时返回0
 */
fun Properties.getIntProperty(key: String, def: Int? = null): Int {
    val stringProperty = getStringProperty(key)
    return if (TextUtils.isEmpty(stringProperty)) {
        def ?: 0
    } else try {
        stringProperty!!.trim { it <= ' ' }.toInt()
    } catch (e: Exception) {
        def ?: 0
    }
}

fun Properties.getBooleanProperty(key: String): Boolean {
    val stringProperty = getStringProperty(key)
    return if (TextUtils.isEmpty(stringProperty)) {
        false
    } else try {
        java.lang.Boolean.parseBoolean(stringProperty)
    } catch (e: Exception) {
        false
    }
}
