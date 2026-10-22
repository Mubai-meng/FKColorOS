package com.fr.colorostools.utils

import android.annotation.SuppressLint
import org.lsposed.lsparanoid.Obfuscate
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassDataList
import org.luckypray.dexkit.result.FieldDataList
import org.luckypray.dexkit.result.MethodDataList

@Obfuscate
object DexkitUtils {
    val tag = "ColorOSTools"

    private val findClass = "findClass"
    private val findMethod = "findMethod"
    private val findField = "findField"

    /**
     * 创建Dexkit实例
     * @param appPath String
     * @return DexKitBridge?
     */
    fun create(appPath: String): DexKitBridge {
        System.loadLibrary("dexkit")
        return DexKitBridge.create(appPath)
    }

    /**
     * 创建Dexkit安全实例
     * @param appPath String
     * @param result Function1<DexKitBridge, Unit>
     */
    @SuppressLint("DuplicateCreateDexKit")
    fun create(appPath: String, result: (DexKitBridge) -> Unit) {
        System.loadLibrary("dexkit")
        DexKitBridge.create(appPath).use { result(it) }
    }

    /**
     * 检查搜索到的类列表并打印LOG
     * @receiver ClassDataList
     * @param instance String
     * @param onlyOne Boolean
     * @param isDebug Boolean
     * @return ClassDataList
     */
    fun ClassDataList.checkDataList(
        instance: String, onlyOne: Boolean = true, isDebug: Boolean = false
    ): ClassDataList {
        when {
            isNullOrEmpty() -> LogUtils.e(tag, "check", "$instance -> $findClass isNullOrEmpty")
            size != 1 && (isDebug || onlyOne) -> {
                if (isDebug) {
                    LogUtils.d(tag, "check", 
                        "$instance -> $findClass size ($size) | onlyOne: $onlyOne"
                    )
                } else LogUtils.e(tag, "check", "$instance -> $findClass size ($size)")
                if (isDebug) forEachIndexed { index, it ->
                    LogUtils.d(tag, "check", "$instance -> $findClass ($index) | ${it.name}")
                }
            }

            size == 1 -> if (isDebug) LogUtils.d(tag, "check", 
                "$instance -> $findClass ${single().name}"
            )
        }
        return this
    }

    /**
     * 检查搜索到的方法列表并打印LOG
     * @receiver MethodDataList
     * @param instance String
     * @param onlyOne Boolean
     * @param isDebug Boolean
     * @return MethodDataList
     */
    fun MethodDataList.checkDataList(
        instance: String, onlyOne: Boolean = true, isDebug: Boolean = false
    ): MethodDataList {
        when {
            isNullOrEmpty() -> LogUtils.e(tag, "check", "$instance -> $findMethod isNullOrEmpty")
            size != 1 && (isDebug || onlyOne) -> {
                if (isDebug) {
                    LogUtils.d(tag, "check", 
                        "$instance -> $findMethod size ($size) | onlyOne: $onlyOne"
                    )
                } else LogUtils.e(tag, "check", "$instance -> $findMethod size ($size)")
                if (isDebug) forEachIndexed { index, it ->
                    LogUtils.d(tag, "check", 
                        "$instance -> $findMethod ($index) | ${it.className} | ${it.methodName}"
                    )
                }
            }

            size == 1 -> if (isDebug) {
                LogUtils.d(tag, "check", 
                    "$instance -> $findMethod Method -> ${single().className} | ${single().methodName}"
                )
                LogUtils.d(tag, "check", 
                    "$instance -> $findMethod Type -> ${single().paramTypeNames} | ${single().returnTypeName}"
                )
            }
        }
        return this
    }

    /**
     * 检查搜索到的Field列表并打印LOG
     * @receiver FieldDataList
     * @param instance String
     * @param onlyOne Boolean
     * @param isDebug Boolean
     * @return FieldDataList
     */
    fun FieldDataList.checkDataList(
        instance: String, onlyOne: Boolean = true, isDebug: Boolean = false
    ): FieldDataList {
        when {
            isNullOrEmpty() -> LogUtils.e(tag, "check", "$instance -> $findField isNullOrEmpty")
            size != 1 && (isDebug || onlyOne) -> {
                if (isDebug) {
                    LogUtils.d(tag, "check", 
                        "$instance -> $findField size ($size) | onlyOne: $onlyOne"
                    )
                } else LogUtils.e(tag, "check", "$instance -> $findField size ($size)")
                if (isDebug) forEachIndexed { index, it ->
                    LogUtils.d(tag, "check", 
                        "$instance -> $findField ($index) | ${it.className} | ${it.fieldName} | ${it.typeName}"
                    )
                }
            }

            size == 1 -> if (isDebug) {
                LogUtils.d(tag, "check", "$instance -> $findField Class -> ${single().className}")
                LogUtils.d(tag, "check", 
                    "$instance -> $findField Field -> ${single().fieldName} | ${single().typeName}"
                )
            }
        }
        return this
    }
}