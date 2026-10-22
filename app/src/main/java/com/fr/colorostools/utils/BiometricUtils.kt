package com.fr.colorostools.utils

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.fr.colorostools.BuildConfig
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
object BiometricUtils {

    val TAG = "BiometricUtils"

    fun createPromptInfo(): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder().apply {
            setTitle("Biometric")
            setSubtitle(BuildConfig.APPLICATION_ID)
            setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        }.build()
    }

    fun showBiometricPrompt(
        activity: FragmentActivity, onSucceed: ((BiometricPrompt.AuthenticationResult) -> Unit)? = null,
        onError: ((Int, CharSequence) -> Unit)? = null, onFailed: (() -> Unit)? = null
    ) {
        return BiometricPrompt(
            activity, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSucceed?.let { it(result) }
                }

                override fun onAuthenticationFailed() {
                    onFailed?.let { it() }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError?.let { it(errorCode, errString) }
                }
            }).authenticate(createPromptInfo())
    }

    fun showBiometricPrompt(
        activity: FragmentActivity, callback: BiometricPrompt.AuthenticationCallback
    ) {
        return BiometricPrompt(activity, activity.mainExecutor, callback).authenticate(
            createPromptInfo()
        )
    }

}