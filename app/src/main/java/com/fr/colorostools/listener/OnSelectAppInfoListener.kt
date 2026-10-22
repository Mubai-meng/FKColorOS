package com.fr.colorostools.listener

import org.lsposed.lsparanoid.Obfuscate
import com.fr.colorostools.data.AppInfo

@Obfuscate
interface OnSelectAppInfoListener {
    fun resultSelectAppInfos(list: ArrayList<AppInfo>)
}