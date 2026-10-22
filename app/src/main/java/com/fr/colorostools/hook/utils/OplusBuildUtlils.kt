package com.fr.colorostools.hook.utils

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.extension.toClassOrNull
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Suppress("unused")
class OplusBuildUtlils(val classLoader: ClassLoader? = null) {

    val clazz = "com.oplus.os.OplusBuild".toClass(classLoader)
    val osdkVersionCodesClazz = "${clazz.name}\$OsdkVersionCodes".toClassOrNull(classLoader)

    val OSVERSIONS = arrayOf(
        "V1.0", "V1.2", "V1.4", "V2.0", "V2.1", "V3.0", "V3.1", "V3.2", "V5.0", "V5.1",
        "V5.2", "V6.0", "V6.1", "V6.2", "V6.7", "V7", "V7.1", "V7.2", "V11", "V11.1",
        "V11.2", "V11.3", "V12", "V12.1", "V12.2", "V13", "V13.1", "V13.1.1", "V13.2", "V14.0",
        "V14.0.1", "V14.0.2", "V14.1.0", "V15.0.0", "V15.0.1", "V15.0.2", "V16.0.0", "V16.1.0", "V16.2.0", "V17.0.0",
        "null"
    )

    val getOSVersions get() = clazz.resolve().firstField { name = "VERSIONS" }.get<Array<String>>()

    val getOsdkVersionCodes get() = osdkVersionCodesClazz?.fields?.map { it.name }?.sorted()

}