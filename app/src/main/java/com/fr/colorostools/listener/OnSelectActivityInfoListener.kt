package com.fr.colorostools.listener

import android.content.pm.ActivityInfo
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
interface OnSelectActivityInfoListener {
    fun resultSelectActivityInfos(list: ArrayList<ActivityInfo>)
}