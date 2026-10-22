package com.fr.colorostools.data

import org.lsposed.lsparanoid.Obfuscate
import java.io.Serializable

@Obfuscate
data class DonateInfo(
    val name: String,
    val money: Double,
    val details: Int,
    val unit: String = "RMB"
) : Serializable

@Obfuscate
data class DonateDetailInfo(
    val name: String,
    val time: String,
    val channel: String,
    val money: Double,
    val order: String,
    val unit: String = "RMB",
) : Serializable