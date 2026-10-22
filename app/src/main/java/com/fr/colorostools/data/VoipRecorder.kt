package com.fr.colorostools.data

import org.lsposed.lsparanoid.Obfuscate
import java.io.Serializable

@Obfuscate
data class VoipRecorder(
    val packName: String,
    val appName: String,
    val activity: String = ""
) : Serializable
