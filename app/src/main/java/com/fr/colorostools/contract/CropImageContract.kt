package com.fr.colorostools.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageView
import com.canhub.cropper.parcelable
import com.highcapable.betterandroid.system.extension.component.Intent
import com.fr.colorostools.data.CropImageContractOptions
import com.fr.colorostools.ui.activity.CropImageActivity
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
class CropImageContract :
    ActivityResultContract<Pair<String, CropImageContractOptions>, Pair<String, CropImageView.CropResult>>() {

    override fun createIntent(
        context: Context, input: Pair<String, CropImageContractOptions>
    ): Intent {
        return Intent<CropImageActivity>(context).apply {
            putExtra(
                CropImage.CROP_IMAGE_EXTRA_BUNDLE,
                Bundle(3).apply {
                    putString("key", input.first)
                    putParcelable(CropImage.CROP_IMAGE_EXTRA_SOURCE, input.second.uri)
                    putParcelable(CropImage.CROP_IMAGE_EXTRA_OPTIONS, input.second.cropImageOptions)
                },
            )
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?)
            : Pair<String, CropImageView.CropResult> {
        val key = intent?.getStringExtra("key") ?: ""
        val result = intent?.parcelable<CropImage.ActivityResult>(CropImage.CROP_IMAGE_EXTRA_RESULT)
        return if (result == null || resultCode == Activity.RESULT_CANCELED) {
            Pair("", CropImage.CancelledResult)
        } else {
            Pair(key, result)
        }
    }

}