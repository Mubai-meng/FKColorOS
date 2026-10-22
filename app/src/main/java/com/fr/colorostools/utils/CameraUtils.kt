package com.fr.colorostools.utils

import android.content.Context
import org.lsposed.lsparanoid.Obfuscate
import com.fr.colorostools.R
import com.fr.colorostools.data.CameraFilter

@Obfuscate
object CameraUtils {

    fun getCameraFilters(context: Context): ArrayList<CameraFilter> {
        context.apply {
            return ArrayList<CameraFilter>().apply {
                add(CameraFilter("master_filter", getString(R.string.camera_filter_master)))
                add(
                    CameraFilter(
                        "jiangwen_filter", getString(R.string.camera_filter_jiangwen)
                    )
                )
                add(
                    CameraFilter(
                        "grand_tour_filter", getString(R.string.camera_filter_grand_tour)
                    )
                )
                add(
                    CameraFilter(
                        "vignette_grain_filter",
                        getString(R.string.camera_filter_vignette_grain)
                    )
                )
                add(
                    CameraFilter(
                        "desert_filter", getString(R.string.camera_filter_desert)
                    )
                )
                add(
                    CameraFilter(
                        "tol_filter", getString(R.string.camera_filter_tol)
                    )
                )
                add(
                    CameraFilter(
                        "os15_zhi_gan_filter", getString(R.string.camera_filter_zhi_gan)
                    )
                )
                add(
                    CameraFilter(
                        "jzk_filter", getString(R.string.camera_filter_jzk)
                    )
                )
            }
        }
    }

    fun getPortraitCameraFilters(context: Context): ArrayList<CameraFilter> {
        context.apply {
            return ArrayList<CameraFilter>().apply {
                add(CameraFilter("retention", getString(R.string.camera_filter_retention)))
                add(
                    CameraFilter(
                        "bokeh_flare_portrait",
                        getString(R.string.camera_filter_bokeh_flare_portrait)
                    )
                )
            }
        }
    }

    fun getVideoCameraFilters(context: Context): ArrayList<CameraFilter> {
        context.apply {
            return ArrayList<CameraFilter>().apply {
                add(
                    CameraFilter(
                        "color_extraction",
                        getString(R.string.camera_filter_color_extraction)
                    )
                )
                add(CameraFilter("retention", getString(R.string.camera_filter_retention)))
                add(
                    CameraFilter(
                        "bokeh_flare_portrait",
                        getString(R.string.camera_filter_bokeh_flare_portrait)
                    )
                )
            }
        }
    }

}