package com.fr.colorostools.data

import kotlinx.serialization.Serializable
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Serializable
data class MemcConfigPackage(
    var packName: String,
    var rate: String,
    var type: String
)