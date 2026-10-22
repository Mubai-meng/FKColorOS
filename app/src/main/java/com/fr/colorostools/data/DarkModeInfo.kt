package com.fr.colorostools.data

import kotlinx.serialization.Serializable
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Serializable
data class DarkModeInfo(
    var packName: String,
    var curType: Int = 0,
)