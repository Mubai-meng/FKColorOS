package com.fr.colorostools.data

import org.lsposed.lsparanoid.Obfuscate
import java.io.Serializable

@Obfuscate
data class DisplayMode(
    var id: Int,
    var width: Int? = null,
    var height: Int? = null,
    var xDpi: Float? = null,
    var yDpi: Float? = null,
    var refreshRate: Float? = null,
) : Serializable {

    constructor() : this(-1)

}