package com.fr.colorostools.listener

import com.fr.colorostools.data.AppIntentInfo
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
interface OnSelectIntentInfoListener {
    fun resultSelectIntentInfos(list: ArrayList<AppIntentInfo>)
}