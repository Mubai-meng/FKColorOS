package com.fr.colorostools.data

import org.lsposed.lsparanoid.Obfuscate
import java.io.Serializable

@Obfuscate
data class CameraFilter(
    val key: String,
    val title: String,
    var isEnable: Boolean = false
) : Serializable
