package com.fr.colorostools.utils

import org.lsposed.lsparanoid.Obfuscate
import org.json.JSONObject
import java.io.File

@Suppress("unused")
@Obfuscate
object DevicesConfigUtils {
    private val configName = "/odm/etc/devices_config/devices_config.json"
    private var configFile = File(configName)
    private var deviceConfig: JSONObject? = null

    private var chargeConfig: JSONObject? = null
    private var otgConfig: JSONObject? = null
    private var shouderConfig: JSONObject? = null
    private var usbConfig: JSONObject? = null
    private var vibratorConfig: JSONObject? = null
    private var lightsWhiteConfig: JSONObject? = null
    private var wirelessChargeConfig: JSONObject? = null

    init {
        if (deviceConfig == null) deviceConfig = safeOfNull {
            JSONObject(configFile.readText())
        }
        if (chargeConfig == null) chargeConfig = deviceConfig?.optJSONObject("charge")
        if (otgConfig == null) otgConfig = deviceConfig?.optJSONObject("otg")
        if (shouderConfig == null) shouderConfig = deviceConfig?.optJSONObject("shouder")
        if (usbConfig == null) usbConfig = deviceConfig?.optJSONObject("usb")
        if (vibratorConfig == null) vibratorConfig = deviceConfig?.optJSONObject("vibrator")
        if (lightsWhiteConfig == null) lightsWhiteConfig =
            deviceConfig?.optJSONObject("lights_white")
        if (wirelessChargeConfig == null) wirelessChargeConfig =
            deviceConfig?.optJSONObject("wireless_charge")
    }

    /**
     * 是否为串联电池
     */
    val isSeriesDualBattery
        get() : Boolean? = chargeConfig?.optBoolean("series_dual_battery_support")

    /**
     * 是否为并联电池
     */
    val isParallelDualBattery
        get() : Boolean? = chargeConfig?.optBoolean("parallel_dual_battery_support")


    val isVBatDeviation
        get() : Boolean? = chargeConfig?.optBoolean("qg_vbat_deviation_support")


    val isAirSVOOCSupport
        get() = wirelessChargeConfig?.optBoolean("air_svooc_support")


    val isAirVOOCSupport
        get() = wirelessChargeConfig?.optBoolean("air_vooc_support")

}