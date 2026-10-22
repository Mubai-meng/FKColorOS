package com.fr.colorostools.data

import android.net.Uri
import com.canhub.cropper.CropImageOptions
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
data class CropImageContractOptions(
    val uri: Uri?,
    val cropImageOptions: CropImageOptions,
)
