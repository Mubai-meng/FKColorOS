@file:Suppress("unused")

package com.fr.colorostools.utils

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 格式化Date
 * @param format String 格式
 * @param param Any? 要格式的对象
 * @param locale Locale? 区域
 * @return String
 */
fun formatDate(format: String, param: Any? = null, locale: Locale? = null): String {
    return SimpleDateFormat(format, locale ?: Locale.getDefault()).format(param ?: Date())
}

/**
 * 格式化UTC时间戳
 * @param format String
 * @param timeMillis Long
 * @return String?
 */
fun formatDateTimeMillis(format: String, timeMillis: Long): String? {
    val instant = Instant.ofEpochMilli(timeMillis).atOffset(ZoneOffset.UTC)
    return DateTimeFormatter.ofPattern(format).format(instant)
}

/**
 * 格式化小数点
 * @receiver Number
 * @param decimals Int
 * @return String
 */
fun Number.formatDecimals(decimals: Int): String {
    return "%.${decimals}f".format(this)
}

/**
 * 清理不可见字符
 */
fun cleanInvisibleChars(input: String): String {
    // 只移除开头的不可见字符，保留中间和末尾的空格
    return input.replace(Regex("[\u0000-\u001F\u007F]"), "")
}

/**
 * 格式化字符串空格显示
 * @param input String
 */
fun formatSpace(input: String): String {
    return input
        // 1. 移除所有控制字符(0x00-0x1F和0x7F)
        .replace(Regex("[\u0000-\u001F\u007F]"), "")
        // 2. 找到第一个可见字符位置并截取
        .let { cleaned ->
            cleaned.indexOfFirst { it.isDefined() && !it.isWhitespace() }.let { firstVisibleIndex ->
                when {
                    firstVisibleIndex == -1 -> ""  // 如果没有可见字符，返回空字符串
                    else -> cleaned.substring(firstVisibleIndex)
                }
            }
        }
        // 3. 合并中间多个空格为单个空格
        .replace(Regex("\\s{2,}"), " ")
        // 4. 移除结尾空格
        .replace(Regex("\\s+$"), "")
        // 5. 移除开头空格（确保前面步骤没有遗漏）
        .replace(Regex("^\\s+"), "")
    //\\p{Alpha} 匹配任何字母字符（包括大写和小写），等价于 [a-zA-Z]
    //\\p{L}  // 匹配任何语言的字母
//    val pattern = Pattern.compile("\\p{L}")
//    val matcher = pattern.matcher(input)
//    if (!matcher.find()) return input
//    return input.substring(matcher.start())
}

/**
 * 格式化文件大小
 * @param size Float
 * @return String
 */
fun formatFileSize(size: Float?): String {
    if (size == null || size.isInfinite() || size.isNaN()) return size.toString()
    return if (size >= (1024 * 1024 * 1024)) {
        DecimalFormat("0.00").format(size / (1024 * 1024 * 1024)).toString() + " GB"
    } else if (size >= (1024 * 1024)) {
        DecimalFormat("0.00").format(size / (1024 * 1024)).toString() + " MB"
    } else if (size >= (1024)) {
        DecimalFormat("0.00").format(size / (1024)).toString() + " KB"
    } else "$size B"
}

/**
 * 截取字符串中的数字
 */
val String.filterNumber get() = replace("\\D".toRegex(), "")

/**
 * 替换字符串空格
 */
val String.replaceSpace get() = replace(" ", "")

/**
 * 替换字符串空白行
 */
val String.replaceBlankLine: String
    get() {
        if (contains("\n").not()) return this
        val formatList = split("\n")
        return formatStringAuto(formatList, "\n", true)
    }

/**
 * 移除空格与空行
 */
val String.replaceBlankAndLine: String
    get() {
        val listString = replaceSpace
        if (listString.contains("\n").not()) return listString
        val formatList = listString.split("\n")
        return formatStringAuto(formatList, "\n", false)
    }

/**
 * 格式化字符串自动添加文本
 * @param formats List<String?> 要格式化的字符数组
 * @param text String 要自动添加的文本
 * @param allowNull Boolean 允许格式化空字符
 * @param allowRepeat Boolean 允许重复添加文本
 * @return String
 */
fun formatStringAuto(
    formats: List<CharSequence?>, text: String,
    allowNull: Boolean = true, allowRepeat: Boolean = true
): String {
    if (formats.isEmpty()) return ""
    require(text.isNotEmpty()) { "Separator text cannot be empty" }  // 改为检查empty而不是blank

    val builder = StringBuilder()
    var previousElementAdded = false

    formats.forEachIndexed { index, str ->
        // 检查是否应该跳过当前元素
        val shouldSkip = when {
            str == null -> !allowNull
            str.isBlank() -> !allowNull  // 对blank元素使用allowNull规则
            !allowRepeat && str == text -> true
            else -> false
        }

        if (shouldSkip) return@forEachIndexed

        // 如果不是第一个元素且前一个元素已添加，才添加分隔符
        if (previousElementAdded) {
            builder.append(text)
        }

        builder.append(str)
        previousElementAdded = true
    }

    return builder.toString()
}

/**
 * 格式化颜色透明度
 * @param baseColor 基本颜色
 * @param alpha 透明度 0f～1f
 * @return
 */
fun formatColorAlpha(baseColor: Int, alpha: Float): Int {
    return baseColor and 0x00ffffff or ((alpha * 255.0f).roundToInt() shl 24)
}

/**
 * 字符串转毫秒
 * @param input String
 * @return Long
 */
fun convertToMillis(input: String): Long {
    val regex = Regex("""(\d+)([WwDdHhMmSs])""")
    val matchResult = regex.find(input)
    return if (matchResult != null) {
        val (value, unit) = matchResult.destructured
        val num = value.toLong()
        when (unit.uppercase()) {
            "W" -> num * 7 * 24 * 60 * 60 * 1000
            "D" -> num * 24 * 60 * 60 * 1000
            "H" -> num * 60 * 60 * 1000
            "M" -> num * 60 * 1000
            "S" -> num * 1000
            else -> -1
        }
    } else -1
}