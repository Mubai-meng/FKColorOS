package com.fr.colorostools.data

import kotlinx.serialization.Serializable
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Serializable
data class AppVerInfo(
    var name: String,
    var packName: String,
    var versionName: String,
    var versionCode: Long,
    var versionCommit: String
)